/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.ui

import android.content.Context
import android.content.Intent
import it.daprile.keepapp.FeatureFlags
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.session.TrainingPlanConfig
import it.daprile.keepapp.platform.audio.BluetoothLatencyCalibrator
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.EarSyncTester
import it.daprile.keepapp.platform.audio.KeepAppAudioEngine
import it.daprile.keepapp.platform.audio.PatternAssetLoader
import it.daprile.keepapp.platform.session.KeepAppSessionService
import it.daprile.keepapp.platform.session.SessionHistoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * State holder principale dell'app (`Step B`), che coordina i motori audio,
 * il calibratore Bluetooth, la sessione live e il salvataggio di `ground_truth.json`.
 */
class KeepAppUiStateHolder(private val appContext: Context) {

    val patterns: List<RhythmPattern> = PatternAssetLoader.loadAll(appContext)
    val manualAudioEngine = KeepAppAudioEngine(initialPattern = patterns.first())
    val calibrator = BluetoothLatencyCalibrator(context = appContext)
    val earSyncTester = EarSyncTester(appContext)
    val profileStore = BtLatencyProfileStore(appContext)
    val historyStore = SessionHistoryStore(appContext)

    init {
        val curPrefs = historyStore.loadPreferences()
        if (!FeatureFlags.ENABLE_TRAINING_PLAN || !FeatureFlags.ENABLE_VOICE_COACH_TTS) {
            historyStore.savePreferences(
                curPrefs.copy(
                    voiceCoachEnabled = if (FeatureFlags.ENABLE_VOICE_COACH_TTS) curPrefs.voiceCoachEnabled else false,
                    trainingPlan = if (FeatureFlags.ENABLE_TRAINING_PLAN) curPrefs.trainingPlan else TrainingPlanConfig()
                )
            )
        }
    }

    fun toggleLiveSession(isCurrentlyActive: Boolean) {
        if (isCurrentlyActive) {
            KeepAppSessionService.stopSession(appContext)
        } else {
            manualAudioEngine.stop()
            calibrator.cancel()
            earSyncTester.stop()
            KeepAppSessionService.startSession(appContext)
        }
    }

    fun sendServiceAction(intentModifier: Intent.() -> Unit) {
        appContext.startService(
            Intent(appContext, KeepAppSessionService::class.java).apply(intentModifier)
        )
    }

    fun setLiveMode(mode: AudioMode) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_MODE
            putExtra(KeepAppSessionService.EXTRA_AUDIO_MODE, mode.name)
        }
    }

    fun setLivePattern(pattern: RhythmPattern) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_PATTERN
            putExtra(KeepAppSessionService.EXTRA_PATTERN_ID, pattern.id)
        }
    }

    fun setLiveAutoLevel(enabled: Boolean) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_AUTO_LEVEL
            putExtra(KeepAppSessionService.EXTRA_AUTO_LEVEL, enabled)
        }
    }

    fun setLiveVoiceCoach(enabled: Boolean) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_VOICE_COACH
            putExtra(KeepAppSessionService.EXTRA_VOICE_COACH, enabled)
        }
    }

    fun setLiveManualLevel(levelIndex: Int) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_MANUAL_LEVEL
            putExtra(KeepAppSessionService.EXTRA_LEVEL_INDEX, levelIndex)
        }
    }

    fun setDataCollectionMode(enabled: Boolean) {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_SET_DATA_COLLECTION_MODE
            putExtra(KeepAppSessionService.EXTRA_DATA_COLLECTION, enabled)
        }
    }

    fun addManualMarker() {
        sendServiceAction {
            action = KeepAppSessionService.ACTION_ADD_MARKER
        }
    }

    fun toggleManualRun(isCurrentlyRunning: Boolean) {
        if (isCurrentlyRunning) {
            manualAudioEngine.stop()
        } else {
            KeepAppSessionService.stopSession(appContext)
            calibrator.cancel()
            earSyncTester.stop()
            manualAudioEngine.start()
        }
    }

    fun startContactCalibration() {
        KeepAppSessionService.stopSession(appContext)
        manualAudioEngine.stop()
        earSyncTester.stop()
        calibrator.startContactImpulseCalibration()
    }

    fun startTapCalibration() {
        KeepAppSessionService.stopSession(appContext)
        manualAudioEngine.stop()
        earSyncTester.stop()
        calibrator.startTapToBeatCalibration()
    }

    fun startSpeakerCheck() {
        KeepAppSessionService.stopSession(appContext)
        manualAudioEngine.stop()
        earSyncTester.stop()
        calibrator.startSpeakerLoopbackCheck()
    }

    fun startBodyTapCheck() {
        KeepAppSessionService.stopSession(appContext)
        manualAudioEngine.stop()
        earSyncTester.stop()
        calibrator.startBodyTapMicDelayTest()
    }

    fun startEarSync(advanceMs: Float) {
        KeepAppSessionService.stopSession(appContext)
        manualAudioEngine.stop()
        calibrator.cancel()
        earSyncTester.start(advanceMs)
    }

    fun saveGroundTruthJson(
        sessionDirPath: String,
        steps: Int,
        positionCode: String,
        activityCode: String,
        environmentCode: String,
        note: String,
        markerRelTimesSec: List<Float>
    ): File? {
        val dir = File(sessionDirPath)
        if (!dir.isDirectory) return null

        val interval = if (markerRelTimesSec.size >= 2) "markers" else "session"
        val realStopsArr = JSONArray()
        if (markerRelTimesSec.size >= 4) {
            val stopPair = JSONArray().apply {
                put(markerRelTimesSec[1].toDouble())
                put(markerRelTimesSec[2].toDouble())
            }
            realStopsArr.put(stopPair)
        }

        val json = JSONObject().apply {
            put("steps", steps)
            put("interval", interval)
            put("position", positionCode)
            put("activity", activityCode)
            put("environment", environmentCode)
            if (realStopsArr.length() > 0) {
                put("real_stops", realStopsArr)
            }
            if (note.isNotBlank()) {
                put("note", note.trim())
            }
        }
        val outFile = File(dir, "ground_truth.json")
        outFile.writeText(json.toString(2), Charsets.UTF_8)
        return outFile
    }

    fun release() {
        calibrator.cancel()
        earSyncTester.stop()
        manualAudioEngine.stop()
    }
}
