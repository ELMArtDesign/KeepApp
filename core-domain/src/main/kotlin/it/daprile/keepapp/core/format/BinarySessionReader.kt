/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.format

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Intestazione decodificata da un file `sensors.bin` (32 byte).
 */
data class ParsedFileHeader(
    val magic: String,
    val version: Short,
    val recordSizeBytes: Short,
    val flags: Int,
    val monotonicStartNs: Long,
    val unixStartMs: Long
)

/**
 * Singolo record decodificato da `sensors.bin` (32 byte) per il Replay Harness JVM (`:replay` e test).
 */
data class ParsedBinaryRecord(
    val timestampNs: Long,
    val recordType: Byte,
    val flags: Byte,
    val reserved: Short,
    val f0: Float,
    val f1: Float,
    val f2: Float,
    val f3: Float,
    val f4: Float,
    val q0: Long,
    val q1: Long,
    val i2: Int,
    val d0: Double,
    val d1: Double
)

/**
 * Metadati essenziali estratti da `manifest.json` senza alcuna dipendenza Android (`org.json`).
 */
data class ParsedManifestSummary(
    val sessionFolder: String,
    val position: PhonePosition,
    val activityKind: ActivityKind,
    val headphonesName: String,
    val monotonicStartNs: Long,
    val durationSeconds: Double,
    val deviceModel: String,
    val hasBarometer: Boolean,
    val pureSurvivalMode: Boolean
)

/**
 * Lettore JVM puro per i file `sensors.bin` e `manifest.json` di una sessione registrata.
 */
object BinarySessionReader {

    /**
     * Legge l'header e tutti i record da un file `sensors.bin`.
     */
    fun readSensorsBin(binFile: File): Pair<ParsedFileHeader, List<ParsedBinaryRecord>> {
        require(binFile.exists() && binFile.length() >= BinaryFormat.HEADER_SIZE_BYTES) {
            "File sensors.bin non valido o mancante: ${binFile.absolutePath}"
        }

        val records = ArrayList<ParsedBinaryRecord>(
            ((binFile.length() - BinaryFormat.HEADER_SIZE_BYTES) / BinaryFormat.RECORD_SIZE_BYTES).toInt().coerceAtLeast(16)
        )

        BufferedInputStream(FileInputStream(binFile), 65_536).use { input ->
            val headerBytes = ByteArray(BinaryFormat.HEADER_SIZE_BYTES)
            val headerRead = input.readNBytes(headerBytes, 0, BinaryFormat.HEADER_SIZE_BYTES)
            require(headerRead == BinaryFormat.HEADER_SIZE_BYTES) { "Header sensors.bin incompleto" }

            val hBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            hBuf.get(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            require(magic == BinaryFormat.MAGIC_STRING) {
                "Magic number non valido in sensors.bin: '$magic' (atteso '${BinaryFormat.MAGIC_STRING}')"
            }
            val version = hBuf.short
            val recordSize = hBuf.short
            val flags = hBuf.int
            val monotonicStartNs = hBuf.long
            val unixStartMs = hBuf.long

            val header = ParsedFileHeader(
                magic = magic,
                version = version,
                recordSizeBytes = recordSize,
                flags = flags,
                monotonicStartNs = monotonicStartNs,
                unixStartMs = unixStartMs
            )

            val recBytes = ByteArray(BinaryFormat.RECORD_SIZE_BYTES)
            while (true) {
                val n = input.readNBytes(recBytes, 0, BinaryFormat.RECORD_SIZE_BYTES)
                if (n < BinaryFormat.RECORD_SIZE_BYTES) break
                records.add(parseRecord(recBytes))
            }

            return header to records
        }
    }

    /**
     * Decodifica un array di 32 byte in un `ParsedBinaryRecord`.
     */
    fun parseRecord(bytes: ByteArray): ParsedBinaryRecord {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ts = buf.getLong(0)
        val type = buf.get(8)
        val flags = buf.get(9)
        val reserved = buf.getShort(10)

        val f0 = buf.getFloat(12)
        val f1 = buf.getFloat(16)
        val f2 = buf.getFloat(20)
        val f3 = buf.getFloat(24)
        val f4 = buf.getFloat(28)

        val q0 = buf.getLong(12)
        val q1 = buf.getLong(20)
        val i2 = buf.getInt(28)

        val d0 = buf.getDouble(12)
        val d1 = buf.getDouble(20)

        return ParsedBinaryRecord(
            timestampNs = ts,
            recordType = type,
            flags = flags,
            reserved = reserved,
            f0 = f0,
            f1 = f1,
            f2 = f2,
            f3 = f3,
            f4 = f4,
            q0 = q0,
            q1 = q1,
            i2 = i2,
            d0 = d0,
            d1 = d1
        )
    }

    /**
     * Estrae i campi principali da `manifest.json` usando espressioni regolari leggere in Kotlin puro
     * (senza dipendere da `org.json` di Android).
     */
    fun readManifestSummary(manifestFile: File, fallbackStartNs: Long = 0L): ParsedManifestSummary {
        if (!manifestFile.exists()) {
            return ParsedManifestSummary(
                sessionFolder = manifestFile.parentFile?.name ?: "unknown",
                position = PhonePosition.ARM,
                activityKind = ActivityKind.WALK,
                headphonesName = "MANKIW-X15",
                monotonicStartNs = fallbackStartNs,
                durationSeconds = 0.0,
                deviceModel = "Unknown",
                hasBarometer = false,
                pureSurvivalMode = false
            )
        }

        val text = manifestFile.readText(Charsets.UTF_8)
        val folder = extractJsonString(text, "session_folder") ?: (manifestFile.parentFile?.name ?: "unknown")
        val posCode = extractJsonString(text, "position_code") ?: "arm"
        val actCode = extractJsonString(text, "activity_code") ?: "walk"
        val headphones = extractJsonString(text, "headphones_name") ?: "MANKIW-X15"
        val monoStart = extractJsonLong(text, "monotonic_start_ns") ?: fallbackStartNs
        val duration = extractJsonDouble(text, "duration_seconds") ?: 0.0
        val model = extractJsonString(text, "model") ?: "Unknown"
        val hasBaro = extractJsonBoolean(text, "has_barometer") ?: false
        val pureSurvival = extractJsonBoolean(text, "pure_survival_mode") ?: false

        return ParsedManifestSummary(
            sessionFolder = folder,
            position = PhonePosition.fromCode(posCode),
            activityKind = ActivityKind.fromCode(actCode),
            headphonesName = headphones,
            monotonicStartNs = monoStart,
            durationSeconds = duration,
            deviceModel = model,
            hasBarometer = hasBaro,
            pureSurvivalMode = pureSurvival
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractJsonDouble(json: String, key: String): Double? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val regex = Regex(""""$key"\s*:\s*(true|false)""")
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }
}
