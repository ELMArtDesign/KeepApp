/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.info

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import it.daprile.keepapp.core.format.AudioDeviceDescriptor
import it.daprile.keepapp.core.format.SensorDescriptor

/**
 * Snapshot completo delle informazioni hardware e di sistema condiviso in `:platform-android`
 * tra `:logger` e `:app`.
 */
data class DeviceSnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildId: String,
    val incrementalVersion: String,
    val hasBarometer: Boolean,
    val sensors: List<SensorDescriptor>,
    val audioDevices: List<AudioDeviceDescriptor>
)

/**
 * Stato termico ed energetico istantaneo per il record periodico THERMAL (ogni 10 s).
 */
data class ThermalAndBatterySnapshot(
    val batteryLevelPercent: Float,
    val batteryTemperatureCelsius: Float,
    val thermalStatus: Int,
    val thermalHeadroom10s: Float
)

/**
 * Raccoglitore condiviso delle informazioni sul dispositivo (`:platform-android`).
 */
object DeviceInfoCollector {

    fun collectDeviceSnapshot(context: Context): DeviceSnapshot {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val rawSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val hasBarometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

        val sensorDescriptors = rawSensors.map { s ->
            val maxHz = if (s.minDelay > 0) 1_000_000f / s.minDelay.toFloat() else 0f
            SensorDescriptor(
                name = s.name ?: "Sconosciuto",
                stringType = s.stringType ?: "type_${s.type}",
                typeId = s.type,
                vendor = s.vendor ?: "Sconosciuto",
                resolution = s.resolution,
                maximumRange = s.maximumRange,
                minDelayUs = s.minDelay,
                maxFrequencyHz = maxHz,
                isWakeUpSensor = s.isWakeUpSensor,
                fifoMaxEventCount = s.fifoMaxEventCount
            )
        }

        val audioDescriptors = collectAudioDevices(audioManager)

        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            product = Build.PRODUCT ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            buildId = Build.ID ?: "",
            incrementalVersion = Build.VERSION.INCREMENTAL ?: "",
            hasBarometer = hasBarometer,
            sensors = sensorDescriptors,
            audioDevices = audioDescriptors
        )
    }

    fun collectAudioDevices(audioManager: AudioManager): List<AudioDeviceDescriptor> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        return devices.map { d ->
            AudioDeviceDescriptor(
                id = d.id,
                productName = d.productName?.toString() ?: "Dispositivo Audio",
                typeId = d.type,
                typeName = audioDeviceTypeToString(d.type),
                isSource = d.isSource,
                isSink = d.isSink
            )
        }
    }

    fun readThermalAndBattery(context: Context): ThermalAndBatterySnapshot {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100f) / scale.toFloat() else -1f

        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10f

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            0
        }

        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                powerManager.getThermalHeadroom(10)
            } catch (_: Exception) {
                Float.NaN
            }
        } else {
            Float.NaN
        }

        return ThermalAndBatterySnapshot(
            batteryLevelPercent = batteryPct,
            batteryTemperatureCelsius = tempCelsius,
            thermalStatus = thermalStatus,
            thermalHeadroom10s = headroom
        )
    }

    fun audioDeviceTypeToString(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE (Capsula)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER (Altoparlante)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET (Cuffie filo + mic)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES (Cuffie filo)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO (Voce HFP bassa qualità)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP (Musica alta qualità)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC (Microfono interno)"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "TYPE_$type"
    }
}
