/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.storage

import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import it.daprile.keepapp.core.format.SessionConfig
import it.daprile.keepapp.platform.info.DeviceSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Scrittore asincrono condiviso in `:platform-android` per `sensors.bin` e `manifest.json`
 * (usato sia da `:logger` in Fase 0 sia da `:app` nelle build Debug in Fase 1A — D45).
 */
class SessionFileWriter(
    sessionsRootDir: File,
    private val config: SessionConfig,
    private val deviceSnapshot: DeviceSnapshot,
    private val monotonicStartNs: Long,
    private val unixStartMs: Long,
    private val appId: String = "it.daprile.moventure.logger",
    private val milestoneTag: String = "Phase1A-M0",
    private val btLatencyMs: Float? = null
) {
    val sessionDir: File

    private val sensorsBinFile: File
    private val manifestJsonFile: File

    private val queue = LinkedBlockingQueue<BinaryRecord>(32_768)
    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null

    val recordsWrittenCount = AtomicLong(0L)
    val droppedRecordsCount = AtomicLong(0L)

    @Volatile
    private var clockVerificationDone = false

    @Volatile
    private var sensorTimestampIsElapsedRealtime = true

    @Volatile
    private var initialSensorClockOffsetMs = 0.0

    init {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestampStr = sdf.format(Date(unixStartMs))
        val suffix = if (config.pureSurvivalMode) "${config.position.code}_puresurvival" else config.position.code
        val folderName = "session_${timestampStr}_${suffix}"
        sessionDir = File(sessionsRootDir, folderName)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
        sensorsBinFile = File(sessionDir, "sensors.bin")
        manifestJsonFile = File(sessionDir, "manifest.json")
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        writeManifestJson(sessionEndedNs = null)

        writerThread = Thread({
            var flags = 0
            if (deviceSnapshot.hasBarometer) flags = flags or 1
            if (config.enableMicrophone) flags = flags or 2
            if (config.pureSurvivalMode) flags = flags or 4

            val headerBytes = BinaryFormat.createFileHeader(
                flags = flags,
                monotonicStartNs = monotonicStartNs,
                unixStartMs = unixStartMs
            )

            FileOutputStream(sensorsBinFile, false).use { fos ->
                BufferedOutputStream(fos, 65_536).use { bos ->
                    bos.write(headerBytes)
                    bos.flush()

                    var lastFlushNs = System.nanoTime()

                    while (running.get() || queue.isNotEmpty()) {
                        val firstRecord = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        bos.write(firstRecord.bytes)
                        var count = 1L

                        var next = queue.poll()
                        while (next != null && count < 512) {
                            bos.write(next.bytes)
                            count++
                            next = queue.poll()
                        }

                        recordsWrittenCount.addAndGet(count)

                        val nowNs = System.nanoTime()
                        if (nowNs - lastFlushNs >= 2_000_000_000L) {
                            bos.flush()
                            lastFlushNs = nowNs
                        }
                    }
                    bos.flush()
                }
            }
        }, "KeepApp-FileWriter").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun enqueue(record: BinaryRecord) {
        if (!running.get()) return
        if (!queue.offer(record)) {
            droppedRecordsCount.incrementAndGet()
        }
    }

    fun recordSensorClockCheck(firstSensorTimestampNs: Long, currentElapsedRealtimeNs: Long) {
        if (clockVerificationDone) return
        val diffNs = firstSensorTimestampNs - currentElapsedRealtimeNs
        val diffMs = diffNs / 1_000_000.0
        initialSensorClockOffsetMs = diffMs
        sensorTimestampIsElapsedRealtime = kotlin.math.abs(diffMs) < 5_000.0
        clockVerificationDone = true
    }

    fun stopAndClose(sessionEndedNs: Long) {
        if (!running.compareAndSet(true, false)) return
        try {
            writerThread?.join(5_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        writeManifestJson(sessionEndedNs = sessionEndedNs)
    }

    private fun writeManifestJson(sessionEndedNs: Long?) {
        try {
            val root = JSONObject().apply {
                put("format_magic", BinaryFormat.MAGIC_STRING)
                put("format_version", BinaryFormat.FORMAT_VERSION.toInt())
                put("record_size_bytes", BinaryFormat.RECORD_SIZE_BYTES)
                put("app_id", appId)
                put("milestone", milestoneTag)

                put("session_folder", sessionDir.name)
                put("position_code", config.position.code)
                put("position_label", config.position.labelIt)
                put("activity_code", config.activityKind.code)
                put("activity_label", config.activityKind.labelIt)
                put("headphones_name", config.headphonesName)
                if (btLatencyMs != null) {
                    put("bt_latency_ms", btLatencyMs.toDouble())
                }
                put("microphone_enabled", config.enableMicrophone)
                put("pure_survival_mode", config.pureSurvivalMode)

                put("unix_start_ms", unixStartMs)
                put("monotonic_start_ns", monotonicStartNs)
                if (sessionEndedNs != null) {
                    put("monotonic_end_ns", sessionEndedNs)
                    put("duration_seconds", (sessionEndedNs - monotonicStartNs) / 1_000_000_000.0)
                }
                put("records_written", recordsWrittenCount.get())
                put("records_dropped", droppedRecordsCount.get())

                put("clock_verification", JSONObject().apply {
                    put("verified", clockVerificationDone)
                    put("sensor_timestamp_is_elapsed_realtime", sensorTimestampIsElapsedRealtime)
                    put("sensor_clock_offset_ms", initialSensorClockOffsetMs)
                })

                put("device", JSONObject().apply {
                    put("manufacturer", deviceSnapshot.manufacturer)
                    put("brand", deviceSnapshot.brand)
                    put("model", deviceSnapshot.model)
                    put("device", deviceSnapshot.device)
                    put("product", deviceSnapshot.product)
                    put("android_release", deviceSnapshot.androidRelease)
                    put("sdk_int", deviceSnapshot.sdkInt)
                    put("build_id", deviceSnapshot.buildId)
                    put("incremental_version", deviceSnapshot.incrementalVersion)
                    put("has_barometer", deviceSnapshot.hasBarometer)
                })

                val sensorsArray = JSONArray()
                for (s in deviceSnapshot.sensors) {
                    sensorsArray.put(JSONObject().apply {
                        put("name", s.name)
                        put("string_type", s.stringType)
                        put("type_id", s.typeId)
                        put("vendor", s.vendor)
                        put("resolution", s.resolution.toDouble())
                        put("max_range", s.maximumRange.toDouble())
                        put("min_delay_us", s.minDelayUs)
                        put("max_frequency_hz", s.maxFrequencyHz.toDouble())
                        put("is_wake_up", s.isWakeUpSensor)
                        put("fifo_max_events", s.fifoMaxEventCount)
                    })
                }
                put("sensors", sensorsArray)

                val audioArray = JSONArray()
                for (a in deviceSnapshot.audioDevices) {
                    audioArray.put(JSONObject().apply {
                        put("id", a.id)
                        put("product_name", a.productName)
                        put("type_id", a.typeId)
                        put("type_name", a.typeName)
                        put("is_source", a.isSource)
                        put("is_sink", a.isSink)
                    })
                }
                put("audio_devices_at_start", audioArray)
            }

            manifestJsonFile.writeText(root.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun getSessionsRootDir(filesDir: File): File {
            val dir = File(filesDir, "sessions")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    }
}
