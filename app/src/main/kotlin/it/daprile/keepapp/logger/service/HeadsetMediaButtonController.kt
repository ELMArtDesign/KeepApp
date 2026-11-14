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

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Gestore dei tasti multimediali (AVRCP) delle cuffie Bluetooth (F4 — Milestone 2).
 *
 * Come funziona sulle cuffie Bluetooth (es. Mankiw a conduzione ossea):
 * - Alcune cuffie inviano ad Android ogni singola pressione come `KEYCODE_MEDIA_PLAY_PAUSE` (85),
 *   `KEYCODE_MEDIA_PLAY` (126), `KEYCODE_MEDIA_PAUSE` (127) o `KEYCODE_HEADSETHOOK` (79).
 * - Altre cuffie gestiscono il doppio tocco nel proprio chip interno e inviano direttamente
 *   `KEYCODE_MEDIA_NEXT` (87) o `KEYCODE_MEDIA_PREVIOUS` (88).
 *
 * Questa classe gestisce ENTRAMBI i casi:
 * - Se arriva direttamente `KEYCODE_MEDIA_NEXT` o `KEYCODE_MEDIA_PREVIOUS`, emette subito
 *   un MARKER "Incrocio" (tipo 2) con 2 pressioni equivalenti.
 * - Se arriva Play/Pause/Hook, attende 450 ms per contare se c'è una seconda pressione ravvicinata:
 *   1 pressione = MARKER "Evento" (tipo 1); 2+ pressioni = MARKER "Incrocio" (tipo 2).
 * - Inoltre mantiene attivo un leggerissimo stream `AudioTrack` silenzioso con `USAGE_MEDIA`
 *   affinché Android/HyperOS instradi sempre gli eventi dei tasti AVRCP alla nostra `MediaSession`
 *   anche a schermo spento.
 */
class HeadsetMediaButtonController(
    private val context: Context,
    private val onMarkerTriggered: (markerFlag: Byte, rawKeyCode: Int, pressCount: Int, keyName: String) -> Unit
) {
    private var mediaSession: MediaSession? = null
    private var silentKeepAliveTrack: AudioTrack? = null
    private var keepAliveThread: Thread? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPressCount = 0
    private var lastRawKeyCode = 0

    private val commitSingleOrDoubleRunnable = Runnable {
        val count = pendingPressCount
        val code = lastRawKeyCode
        pendingPressCount = 0
        if (count <= 0) return@Runnable

        val flag: Byte = if (count == 1) 1 else 2 // 1 = Evento, 2 = Incrocio
        val keyName = KeyEvent.keyCodeToString(code)
        playFeedbackBeep(isDouble = (flag == 2.toByte()))
        onMarkerTriggered(flag, code, count, "$keyName ($code) [${if (flag == 1.toByte()) "1x Evento" else "2x Incrocio"}]")
    }

    fun start(): MediaSession.Token? {
        if (running) return mediaSession?.sessionToken
        running = true

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
        } catch (_: Exception) {
        }

        startSilentAudioKeepAlive()

        val session = MediaSession(context, "KeepAppLoggerMediaSession").apply {
            val stateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f, SystemClock.elapsedRealtime())
            setPlaybackState(stateBuilder.build())

            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        handleKeyDown(keyEvent.keyCode)
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    handleKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY)
                }

                override fun onPause() {
                    handleKeyDown(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }

                override fun onSkipToNext() {
                    handleKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT)
                }

                override fun onSkipToPrevious() {
                    handleKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            })

            isActive = true
        }

        mediaSession = session
        return session.sessionToken
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(commitSingleOrDoubleRunnable)

        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null

        try {
            keepAliveThread?.interrupt()
            keepAliveThread?.join(1_000)
        } catch (_: Exception) {
        }
        keepAliveThread = null

        try {
            silentKeepAliveTrack?.stop()
            silentKeepAliveTrack?.release()
        } catch (_: Exception) {
        }
        silentKeepAliveTrack = null

        try {
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        toneGenerator = null
    }

    private fun handleKeyDown(keyCode: Int) {
        // Se la cuffia traduce già il doppio click in NEXT o PREVIOUS in hardware:
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            handler.removeCallbacks(commitSingleOrDoubleRunnable)
            pendingPressCount = 0
            val keyName = KeyEvent.keyCodeToString(keyCode)
            playFeedbackBeep(isDouble = true)
            onMarkerTriggered(2, keyCode, 2, "$keyName ($keyCode) [2x Incrocio HW]")
            return
        }

        // Altrimenti accumula le pressioni entro 450 ms per distinguere 1 click da 2 click
        lastRawKeyCode = keyCode
        pendingPressCount++
        handler.removeCallbacks(commitSingleOrDoubleRunnable)
        if (pendingPressCount >= 2) {
            // Appena arriva il secondo click, conferma immediatamente "Incrocio"
            commitSingleOrDoubleRunnable.run()
        } else {
            handler.postDelayed(commitSingleOrDoubleRunnable, 450L)
        }
    }

    private fun playFeedbackBeep(isDouble: Boolean) {
        try {
            val tg = toneGenerator ?: return
            if (!isDouble) {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            } else {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Mantiene uno stream AudioTrack con zeri (silenzio) attivo affinché Android e MIUI
     * considerino la nostra MediaSession il player multimediale attivo per i tasti Bluetooth.
     */
    private fun startSilentAudioKeepAlive() {
        val sampleRate = 16_000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }

        silentKeepAliveTrack = track
        track.play()

        val silenceBuf = ByteArray(minBuf)
        keepAliveThread = Thread({
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    track.write(silenceBuf, 0, silenceBuf.size)
                } catch (_: Exception) {
                    break
                }
            }
        }, "KeepApp-MediaButtonKeepAlive").apply {
            isDaemon = true
            start()
        }
    }
}
