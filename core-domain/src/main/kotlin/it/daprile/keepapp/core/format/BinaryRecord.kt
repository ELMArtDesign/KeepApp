/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.daprile.keepapp.core.format

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Definizione formale dei tipi di record e del serializzatore binario a 32 byte
 * per il file `sensors.bin` (Fase 0).
 *
 * Questo modulo è scritto in Kotlin puro (zero dipendenze Android) per poter essere
 * condiviso e testato su qualsiasi piattaforma.
 */
object BinaryFormat {
    /** Magic ASCII di 8 byte all'inizio di sensors.bin */
    const val MAGIC_STRING = "KEEPAPP0"

    /** Versione corrente dello schema binario */
    const val FORMAT_VERSION: Short = 1

    /** Dimensione fissa in byte dell'header e di ciascun record */
    const val HEADER_SIZE_BYTES = 32
    const val RECORD_SIZE_BYTES = 32

    // Codici identificativi dei tipi di record (uint8)
    const val TYPE_ACCEL: Byte = 1
    const val TYPE_GYRO: Byte = 2
    const val TYPE_MAG: Byte = 3
    const val TYPE_BARO: Byte = 4
    const val TYPE_STEP: Byte = 5
    const val TYPE_GPS_A: Byte = 6
    const val TYPE_GPS_B: Byte = 7
    const val TYPE_MARKER: Byte = 8
    const val TYPE_METRONOME_CLICK: Byte = 9
    const val TYPE_AUDIO_SYNC: Byte = 10
    const val TYPE_HEARTBEAT: Byte = 11
    const val TYPE_THERMAL: Byte = 12
    const val TYPE_ROUTE_CHANGE: Byte = 13

    /**
     * Genera i 32 byte di intestazione (Header) del file `sensors.bin` in Little-Endian.
     */
    fun createFileHeader(
        flags: Int,
        monotonicStartNs: Long,
        unixStartMs: Long
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = MAGIC_STRING.toByteArray(Charsets.US_ASCII)
        require(magicBytes.size == 8) { "Il magic number deve essere di 8 byte ASCII" }
        buffer.put(magicBytes)                    // 0..7:   "KEEPAPP0"
        buffer.putShort(FORMAT_VERSION)           // 8..9:   version = 1
        buffer.putShort(RECORD_SIZE_BYTES.toShort()) // 10..11: record_size = 32
        buffer.putInt(flags)                      // 12..15: bitmask opzioni
        buffer.putLong(monotonicStartNs)          // 16..23: elapsedRealtimeNanos iniziale
        buffer.putLong(unixStartMs)               // 24..31: timestamp Unix UTC in ms
        return buffer.array()
    }
}

/**
 * Rappresenta un singolo record binario immutabile da 32 byte pronto per essere
 * accodato allo scrittore su thread dedicato.
 */
class BinaryRecord(val bytes: ByteArray) {

    init {
        require(bytes.size == BinaryFormat.RECORD_SIZE_BYTES) {
            "Ogni BinaryRecord deve occupare esattamente 32 byte (trovati: ${bytes.size})"
        }
    }

    companion object {
        /**
         * Crea un record standard con payload composto da 5 valori Float32 (20 byte).
         */
        fun ofFloats(
            timestampNs: Long,
            recordType: Byte,
            flags: Byte = 0,
            reserved: Short = 0,
            f0: Float = 0f,
            f1: Float = 0f,
            f2: Float = 0f,
            f3: Float = 0f,
            f4: Float = 0f
        ): BinaryRecord {
            val buf = ByteBuffer.allocate(BinaryFormat.RECORD_SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(timestampNs)   // 0..7
            buf.put(recordType)        // 8
            buf.put(flags)             // 9
            buf.putShort(reserved)     // 10..11
            buf.putFloat(f0)           // 12..15
            buf.putFloat(f1)           // 16..19
            buf.putFloat(f2)           // 20..23
            buf.putFloat(f3)           // 24..27
            buf.putFloat(f4)           // 28..31
            return BinaryRecord(buf.array())
        }

        /**
         * Crea un record per un sensore vettoriale 3D (ACCEL, GYRO, MAG) calcolando
         * automaticamente la magnitudine del vettore nel quarto float (`f3`).
         */
        fun ofVector3D(
            timestampNs: Long,
            recordType: Byte,
            accuracy: Int,
            x: Float,
            y: Float,
            z: Float
        ): BinaryRecord {
            val magnitude = sqrt(x * x + y * y + z * z)
            return ofFloats(
                timestampNs = timestampNs,
                recordType = recordType,
                flags = accuracy.coerceIn(0, 255).toByte(),
                reserved = 0,
                f0 = x,
                f1 = y,
                f2 = z,
                f3 = magnitude,
                f4 = 0f
            )
        }

        /**
         * Crea un record `GPS_A` con Latitudine e Longitudine in doppia precisione (`Double` 64-bit)
         * e Quota in `Float` 32-bit (8 + 8 + 4 = 20 byte di payload).
         */
        fun ofGpsA(
            timestampNs: Long,
            satellites: Int,
            latitude: Double,
            longitude: Double,
            altitudeMeters: Float
        ): BinaryRecord {
            val buf = ByteBuffer.allocate(BinaryFormat.RECORD_SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(timestampNs)
            buf.put(BinaryFormat.TYPE_GPS_A)
            buf.put(1.toByte()) // fix valido
            buf.putShort(satellites.coerceIn(0, 32767).toShort())
            buf.putDouble(latitude)       // 12..19 (8 byte)
            buf.putDouble(longitude)      // 20..27 (8 byte)
            buf.putFloat(altitudeMeters)  // 28..31 (4 byte)
            return BinaryRecord(buf.array())
        }

        /**
         * Crea un record `GPS_B` con velocità, rotta (bearing), accuratezza orizzontale/verticale
         * e scarto temporale del fix.
         */
        fun ofGpsB(
            timestampNs: Long,
            hasSpeedAndBearingFlags: Byte,
            speedMps: Float,
            bearingDeg: Float,
            horizontalAccuracyM: Float,
            verticalAccuracyM: Float,
            fixAgeSeconds: Float
        ): BinaryRecord = ofFloats(
            timestampNs = timestampNs,
            recordType = BinaryFormat.TYPE_GPS_B,
            flags = hasSpeedAndBearingFlags,
            reserved = 0,
            f0 = speedMps,
            f1 = bearingDeg,
            f2 = horizontalAccuracyM,
            f3 = verticalAccuracyM,
            f4 = fixAgeSeconds
        )

        /**
         * Crea un record con 2 interi a 64 bit (`Long`) e 1 intero a 32 bit (`Int`) nel payload
         * (usato per `AUDIO_SYNC` e `METRONOME_CLICK` dove i contatori campioni e nanosecondi
         * richiedono precisione intera a 64 bit).
         */
        fun ofTwoLongsAndInt(
            timestampNs: Long,
            recordType: Byte,
            flags: Byte,
            reserved: Short = 0,
            q0: Long,
            q1: Long,
            i2: Int
        ): BinaryRecord {
            val buf = ByteBuffer.allocate(BinaryFormat.RECORD_SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(timestampNs)
            buf.put(recordType)
            buf.put(flags)
            buf.putShort(reserved)
            buf.putLong(q0) // 12..19
            buf.putLong(q1) // 20..27
            buf.putInt(i2)  // 28..31
            return BinaryRecord(buf.array())
        }
    }
}
