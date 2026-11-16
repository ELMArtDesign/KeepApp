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

import android.content.Context
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.session.CadenceTracePoint
import it.daprile.keepapp.core.session.SessionSummaryRecord
import it.daprile.keepapp.core.session.TrainingMode
import it.daprile.keepapp.core.session.TrainingPlanConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Preferenze utente salvate localmente (modalità audio, pattern scelto, livello automatico, voice coach, piano allenamento).
 */
data class UserAudioPreferences(
    val audioMode: AudioMode = AudioMode.RHYTHM_PATTERN,
    val patternId: String = "pulse_steady",
    val autoLevelEnabled: Boolean = true,
    val voiceCoachEnabled: Boolean = true,
    val trainingPlan: TrainingPlanConfig = TrainingPlanConfig()
)

/**
 * Persistenza locale dello storico sessioni e delle preferenze utente (Milestone 5).
 */
class SessionHistoryStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "keepapp_session_history"
        private const val KEY_HISTORY_JSON = "history_json"
        private const val KEY_PREF_MODE = "pref_audio_mode"
        private const val KEY_PREF_PATTERN = "pref_pattern_id"
        private const val KEY_PREF_AUTO_LEVEL = "pref_auto_level"
        private const val KEY_PREF_VOICE_COACH = "pref_voice_coach"
        private const val KEY_PREF_TRAIN_MODE = "pref_train_mode"
        private const val KEY_PREF_FIXED_BPM = "pref_fixed_bpm"
        private const val KEY_PREF_RAMP_START = "pref_ramp_start"
        private const val KEY_PREF_RAMP_END = "pref_ramp_end"
        private const val KEY_PREF_RAMP_MIN = "pref_ramp_min"
        private const val MAX_HISTORY_ENTRIES = 30
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPreferences(): UserAudioPreferences {
        val modeName = prefs.getString(KEY_PREF_MODE, AudioMode.RHYTHM_PATTERN.name)
        val mode = runCatching { AudioMode.valueOf(modeName ?: "") }.getOrDefault(AudioMode.RHYTHM_PATTERN)
        val patternId = prefs.getString(KEY_PREF_PATTERN, "pulse_steady") ?: "pulse_steady"
        val autoLevel = prefs.getBoolean(KEY_PREF_AUTO_LEVEL, true)
        val voiceCoach = prefs.getBoolean(KEY_PREF_VOICE_COACH, true)
        val tModeName = prefs.getString(KEY_PREF_TRAIN_MODE, TrainingMode.ADAPTIVE_ANCHOR.name)
        val tMode = runCatching { TrainingMode.valueOf(tModeName ?: "") }.getOrDefault(TrainingMode.ADAPTIVE_ANCHOR)
        val plan = TrainingPlanConfig(
            mode = tMode,
            fixedTargetBpm = prefs.getFloat(KEY_PREF_FIXED_BPM, 160f),
            rampStartBpm = prefs.getFloat(KEY_PREF_RAMP_START, 150f),
            rampEndBpm = prefs.getFloat(KEY_PREF_RAMP_END, 175f),
            rampDurationMinutes = prefs.getInt(KEY_PREF_RAMP_MIN, 20)
        )
        return UserAudioPreferences(
            audioMode = mode,
            patternId = patternId,
            autoLevelEnabled = autoLevel,
            voiceCoachEnabled = voiceCoach,
            trainingPlan = plan
        )
    }

    fun savePreferences(preferences: UserAudioPreferences) {
        prefs.edit()
            .putString(KEY_PREF_MODE, preferences.audioMode.name)
            .putString(KEY_PREF_PATTERN, preferences.patternId)
            .putBoolean(KEY_PREF_AUTO_LEVEL, preferences.autoLevelEnabled)
            .putBoolean(KEY_PREF_VOICE_COACH, preferences.voiceCoachEnabled)
            .putString(KEY_PREF_TRAIN_MODE, preferences.trainingPlan.mode.name)
            .putFloat(KEY_PREF_FIXED_BPM, preferences.trainingPlan.fixedTargetBpm)
            .putFloat(KEY_PREF_RAMP_START, preferences.trainingPlan.rampStartBpm)
            .putFloat(KEY_PREF_RAMP_END, preferences.trainingPlan.rampEndBpm)
            .putInt(KEY_PREF_RAMP_MIN, preferences.trainingPlan.rampDurationMinutes)
            .apply()
    }

    fun loadHistory(): List<SessionSummaryRecord> {
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<SessionSummaryRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val posName = obj.optString("dominantPosition", PhonePosition.ARM.name)
                val pos = runCatching { PhonePosition.valueOf(posName) }.getOrDefault(PhonePosition.ARM)
                val traceArr = obj.optJSONArray("tracePoints")
                val trace = if (traceArr != null) {
                    List(traceArr.length()) { k ->
                        val pt = traceArr.getJSONObject(k)
                        CadenceTracePoint(
                            elapsedSec = pt.optInt("s", 0),
                            cadenceSpm = pt.optDouble("c", 0.0).toFloat(),
                            targetBpm = pt.optDouble("t", 0.0).toFloat()
                        )
                    }
                } else {
                    emptyList()
                }
                list.add(
                    SessionSummaryRecord(
                        id = obj.optString("id", ""),
                        startedAtMs = obj.optLong("startedAtMs", 0L),
                        durationSeconds = obj.optDouble("durationSeconds", 0.0).toFloat(),
                        totalSteps = obj.optLong("totalSteps", 0L),
                        avgCadenceSpm = obj.optDouble("avgCadenceSpm", 0.0).toFloat(),
                        initialLockedBpm = obj.optDouble("initialLockedBpm", 0.0).toFloat(),
                        finalTargetBpm = obj.optDouble("finalTargetBpm", 0.0).toFloat(),
                        stopCount = obj.optInt("stopCount", 0),
                        pauseCount = obj.optInt("pauseCount", 0),
                        dominantPosition = pos,
                        deviceName = obj.optString("deviceName", "—"),
                        appliedBtLatencyMs = obj.optDouble("appliedBtLatencyMs", 120.0).toFloat(),
                        patternId = obj.optString("patternId", "pulse_steady"),
                        tracePoints = trace
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun addSession(record: SessionSummaryRecord) {
        val updated = (listOf(record) + loadHistory()).take(MAX_HISTORY_ENTRIES)
        val arr = JSONArray()
        for (item in updated) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("startedAtMs", item.startedAtMs)
                    put("durationSeconds", item.durationSeconds.toDouble())
                    put("totalSteps", item.totalSteps)
                    put("avgCadenceSpm", item.avgCadenceSpm.toDouble())
                    put("initialLockedBpm", item.initialLockedBpm.toDouble())
                    put("finalTargetBpm", item.finalTargetBpm.toDouble())
                    put("stopCount", item.stopCount)
                    put("pauseCount", item.pauseCount)
                    put("dominantPosition", item.dominantPosition.name)
                    put("deviceName", item.deviceName)
                    put("appliedBtLatencyMs", item.appliedBtLatencyMs.toDouble())
                    put("patternId", item.patternId)
                    put("tracePoints", JSONArray().apply {
                        for (pt in item.tracePoints) {
                            put(
                                JSONObject().apply {
                                    put("s", pt.elapsedSec)
                                    put("c", pt.cadenceSpm.toDouble())
                                    put("t", pt.targetBpm.toDouble())
                                }
                            )
                        }
                    })
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }
}
