/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Registratore microfonico dedicato per la Fase 0 (F2 / F3 — Milestone 2).
 *
 * Requisiti rispettati:
 * - Cattura mono a 48.000 Hz, 16-bit PCM (`ENCODING_PCM_16BIT`) in `mic_raw.wav`.
 * - Imposta `AudioManager.mode = AudioManager.MODE_NORMAL` e forza come dispositivo preferito
 *   `AudioDeviceInfo.TYPE_BUILTIN_MIC` (microfono interno dello smartphone), evitando che
 *   Android attivi il microfono SCO/HFP delle cuffie Bluetooth.
 * - Ogni ~1 secondo interroga `AudioRecord.getTimestamp(audioTimestamp, TIMEBASE_BOOTTIME)`
 *   ed emette un record binario `AUDIO_SYNC` in `sensors.bin` (contenente `framePosition`,
 *   `nanoTime` e il numero totale di campioni letti) per misurare la deriva del clock audio.
 * - Scrive i campioni su disco in un thread dedicato (`KeepApp-MicWriter`) e aggiorna
 *   l'intestazione RIFF/WAVE finale (44 byte) alla chiusura del file.
 */
class MicrophoneWavRecorder(
    private val context: Context,
    private val wavFile: File,
    private val sessionWriter: SessionFileWriter
) {
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    /** Totale dei campioni (frames mono) scritti in `mic_raw.wav` */
    val totalFramesWritten = AtomicLong(0L)

    /** Conteggio dei record `AUDIO_SYNC` emessi */
    val audioSyncCount = AtomicLong(0L)

    /** Nome e tipo del dispositivo di input effettivamente usato da AudioRecord */
    @Volatile
    var activeInputDeviceSummary: String = "In attesa..."
        private set

    /** Ultima deriva stimata in millisecondi tra clock audio e clock di sistema */
    @Volatile
    var lastClockDriftMs: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isRecording.compareAndSet(false, true)) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Assicura che il sistema rimanga in MODE_NORMAL (mai MODE_IN_COMMUNICATION che attiva SCO/HFP)
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        if (audioManager.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }

        recordingThread = Thread({
            val minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE_HZ * BYTES_PER_FRAME / 5) // almeno 200 ms di buffer

            // Buffer hardware di AudioRecord dimensionato a ~500 ms per evitare overrun a schermo spento
            val recordBufferSize = (minBufBytes * 2).coerceAtLeast(SAMPLE_RATE_HZ * BYTES_PER_FRAME / 2)

            val audioRecord = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufferSize)
                    .build()
            } catch (_: Exception) {
                isRecording.set(false)
                activeInputDeviceSummary = "Errore inizializzazione AudioRecord"
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                isRecording.set(false)
                activeInputDeviceSummary = "AudioRecord non inizializzato"
                return@Thread
            }

            // Cerca e seleziona esplicitamente il microfono interno (TYPE_BUILTIN_MIC)
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val builtinMic = inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
            if (builtinMic != null) {
                audioRecord.setPreferredDevice(builtinMic)
            }

            try {
                audioRecord.startRecording()
            } catch (_: Exception) {
                audioRecord.release()
                isRecording.set(false)
                activeInputDeviceSummary = "Errore avvio startRecording"
                return@Thread
            }

            updateActiveInputDevice(audioRecord.routedDevice)

            val readChunkBytes = 4_800 * BYTES_PER_FRAME // 100 ms per lettura (4800 campioni = 9600 byte)
            val pcmBuffer = ByteArray(readChunkBytes)
            val audioTimestamp = AudioTimestamp()

            var lastSyncElapsedNs = SystemClock.elapsedRealtimeNanos()
            var firstSyncFramePos = -1L
            var firstSyncNanoTime = -1L

            FileOutputStream(wavFile, false).use { fos ->
                BufferedOutputStream(fos, 65_536).use { bos ->
                    // Scrive i 44 byte di intestazione WAV provvisoria (verrà aggiornata a fine sessione)
                    bos.write(createWavHeader(dataSizeBytes = 0L))

                    while (isRecording.get()) {
                        val bytesRead = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                        if (bytesRead > 0) {
                            bos.write(pcmBuffer, 0, bytesRead)
                            val framesReadNow = (bytesRead / BYTES_PER_FRAME).toLong()
                            val totalFrames = totalFramesWritten.addAndGet(framesReadNow)

                            val nowNs = SystemClock.elapsedRealtimeNanos()
                            if (nowNs - lastSyncElapsedNs >= 1_000_000_000L) {
                                lastSyncElapsedNs = nowNs
                                updateActiveInputDevice(audioRecord.routedDevice)

                                // Interroga il timestamp hardware sulla stessa base TIMEBASE_BOOTTIME (elapsedRealtimeNanos)
                                val status = audioRecord.getTimestamp(
                                    audioTimestamp,
                                    AudioTimestamp.TIMEBASE_BOOTTIME
                                )
                                val isValid = (status == AudioRecord.SUCCESS)
                                val hwFramePos = if (isValid) audioTimestamp.framePosition else totalFrames
                                val hwNanoTime = if (isValid) audioTimestamp.nanoTime else nowNs

                                if (isValid) {
                                    if (firstSyncFramePos < 0L) {
                                        firstSyncFramePos = hwFramePos
                                        firstSyncNanoTime = hwNanoTime
                                    } else {
                                        val deltaFrames = hwFramePos - firstSyncFramePos
                                        val expectedAudioNs = (deltaFrames * 1_000_000_000L) / SAMPLE_RATE_HZ
                                        val actualElapsedNs = hwNanoTime - firstSyncNanoTime
                                        lastClockDriftMs = (expectedAudioNs - actualElapsedNs) / 1_000_000f
                                    }
                                }

                                sessionWriter.enqueue(
                                    BinaryRecord.ofTwoLongsAndInt(
                                        timestampNs = nowNs,
                                        recordType = BinaryFormat.TYPE_AUDIO_SYNC,
                                        flags = if (isValid) 1 else 0,
                                        reserved = 0,
                                        q0 = hwFramePos,
                                        q1 = hwNanoTime,
                                        i2 = totalFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                    )
                                )
                                audioSyncCount.incrementAndGet()
                            }
                        }
                    }
                    bos.flush()
                }
            }

            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()

            // Aggiorna i campi dimensione RIFF e data nei primi 44 byte del file WAV
            val finalDataBytes = totalFramesWritten.get() * BYTES_PER_FRAME
            patchWavHeader(wavFile, finalDataBytes)
        }, "KeepApp-MicWriter").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stopAndClose() {
        if (!isRecording.compareAndSet(true, false)) return
        try {
            recordingThread?.join(3_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun updateActiveInputDevice(routed: AudioDeviceInfo?) {
        if (routed != null) {
            val typeStr = DeviceInfoCollector.audioDeviceTypeToString(routed.type)
            activeInputDeviceSummary = "${routed.productName} ($typeStr)"
        }
    }

    private fun createWavHeader(dataSizeBytes: Long): ByteArray {
        val totalDataLen = dataSizeBytes + 36L
        val byteRate = SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_FRAME
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalDataLen.coerceAtMost(0x7FFF_FFFFL).toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // Subchunk1Size per PCM
        buf.putShort(1) // AudioFormat 1 = PCM
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE_HZ)
        buf.putInt(byteRate)
        buf.putShort(BYTES_PER_FRAME.toShort()) // BlockAlign
        buf.putShort(16) // BitsPerSample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSizeBytes.coerceAtMost(0x7FFF_FFFFL).toInt())
        return buf.array()
    }

    private fun patchWavHeader(file: File, dataSizeBytes: Long) {
        if (!file.exists() || file.length() < 44L) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0L)
                raf.write(createWavHeader(dataSizeBytes))
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 1
        const val BYTES_PER_FRAME = 2 // 16-bit mono = 2 byte
    }
}
