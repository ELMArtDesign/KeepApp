/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import it.daprile.keepapp.core.audio.CalibrationMethod

/**
 * Descrittore del dispositivo audio di uscita attivo (cuffie Bluetooth o altoparlante).
 */
data class BtOutputDevice(
    val key: String,
    val displayName: String,
    val isBluetooth: Boolean
)

/**
 * Profilo di latenza calibrato e persistito per un singolo dispositivo Bluetooth (D35 / D43).
 */
data class SavedBtLatencyProfile(
    val deviceKey: String,
    val deviceName: String,
    val latencyMs: Float,
    val stdDevMs: Float,
    val method: CalibrationMethod,
    val calibratedAtMs: Long,
    val supportsDoubleTap: Boolean = false
)

/**
 * Rileva il dispositivo audio Bluetooth attivo e gestisce la persistenza locale delle calibrazioni.
 */
class BtLatencyProfileStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "keepapp_bt_latency_profiles",
        Context.MODE_PRIVATE
    )
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun detectActiveOutputDevice(): BtOutputDevice {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = outputs.firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        dev.type == AudioDeviceInfo.TYPE_HEARING_AID))
        }

        if (btDevice != null) {
            val rawName = btDevice.productName?.toString()?.trim().orEmpty().ifEmpty { "Cuffie Bluetooth" }
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                btDevice.address?.trim().orEmpty()
            } else {
                ""
            }
            val safeKey = if (addr.isNotEmpty() && addr != "00:00:00:00:00:00") {
                "bt_${rawName}_$addr"
            } else {
                "bt_$rawName"
            }
            return BtOutputDevice(
                key = safeKey,
                displayName = rawName,
                isBluetooth = true
            )
        }

        return BtOutputDevice(
            key = "local_speaker",
            displayName = "Altoparlante / Jack",
            isBluetooth = false
        )
    }

    fun save(profile: SavedBtLatencyProfile) {
        val prefix = sanitizeKey(profile.deviceKey)
        prefs.edit()
            .putString("${prefix}_name", profile.deviceName)
            .putFloat("${prefix}_latency_ms", profile.latencyMs)
            .putFloat("${prefix}_stddev_ms", profile.stdDevMs)
            .putString("${prefix}_method", profile.method.name)
            .putLong("${prefix}_timestamp_ms", profile.calibratedAtMs)
            .putBoolean("${prefix}_double_tap", profile.supportsDoubleTap)
            .apply()
    }

    fun markDoubleTapSupported(deviceKey: String, supported: Boolean = true) {
        val prefix = sanitizeKey(deviceKey)
        prefs.edit().putBoolean("${prefix}_double_tap", supported).apply()
    }

    fun isDoubleTapSupported(deviceKey: String): Boolean {
        val prefix = sanitizeKey(deviceKey)
        return prefs.getBoolean("${prefix}_double_tap", false)
    }

    fun load(deviceKey: String): SavedBtLatencyProfile? {
        val prefix = sanitizeKey(deviceKey)
        if (!prefs.contains("${prefix}_latency_ms")) return null
        val name = prefs.getString("${prefix}_name", deviceKey) ?: deviceKey
        val latency = prefs.getFloat("${prefix}_latency_ms", 120f)
        val stdDev = prefs.getFloat("${prefix}_stddev_ms", 0f)
        val methodName = prefs.getString("${prefix}_method", CalibrationMethod.MANUAL.name)
        val method = runCatching { CalibrationMethod.valueOf(methodName ?: "MANUAL") }
            .getOrDefault(CalibrationMethod.MANUAL)
        val ts = prefs.getLong("${prefix}_timestamp_ms", 0L)
        val dbl = prefs.getBoolean("${prefix}_double_tap", false)
        return SavedBtLatencyProfile(
            deviceKey = deviceKey,
            deviceName = name,
            latencyMs = latency,
            stdDevMs = stdDev,
            method = method,
            calibratedAtMs = ts,
            supportsDoubleTap = dbl
        )
    }

    private fun sanitizeKey(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
