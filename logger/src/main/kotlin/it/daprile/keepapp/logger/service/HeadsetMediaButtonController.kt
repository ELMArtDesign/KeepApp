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
 * Gestore dei tasti multimediali (AVRCP) delle cuffie Bluetooth (F4 — aggiornato per D34 in M0).
 *
 * Mappatura D34:
 * - `79` (`KEYCODE_HEADSETHOOK`), `85` (`KEYCODE_MEDIA_PLAY_PAUSE`),
 *   `126` (`KEYCODE_MEDIA_PLAY`), `127` (`KEYCODE_MEDIA_PAUSE`) -> "1 tocco" (se singolo entro 450 ms),
 *   "2 tocchi" (se 2 pressioni entro 450 ms), "3 tocchi" (se 3+ pressioni entro 450 ms).
 * - `87` (`KEYCODE_MEDIA_NEXT`) -> "2 tocchi" (tradotto in hardware dalla cuffia Bluetooth).
 * - `88` (`KEYCODE_MEDIA_PREVIOUS`) -> "3 tocchi" (tradotto in hardware dalla cuffia Bluetooth).
 * - Qualsiasi altro `keyCode` -> registrato fedelmente in `sensors.bin` con il proprio codice intero.
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

    private val commitMultiPressRunnable = Runnable {
        val count = pendingPressCount
        val code = lastRawKeyCode
        pendingPressCount = 0
        if (count <= 0) return@Runnable

        val flag: Byte = when {
            count == 1 -> 1 // 1 tocco (Evento / Pausa-Ripresa)
            count == 2 -> 2 // 2 tocchi (Incrocio / Recap)
            else -> 4       // 3+ tocchi
        }
        val label = when (flag.toInt()) {
            1 -> "1x Tocco (Evento)"
            2 -> "2x Tocchi (Incrocio)"
            else -> "${count}x Tocchi"
        }
        val keyName = KeyEvent.keyCodeToString(code)
        playFeedbackBeep(presses = count.coerceIn(1, 3))
        onMarkerTriggered(flag, code, count, "$keyName ($code) [$label]")
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
        handler.removeCallbacks(commitMultiPressRunnable)

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
        val keyName = KeyEvent.keyCodeToString(keyCode)

        // D34: 87 (KEYCODE_MEDIA_NEXT) = 2 tocchi tradotti in hardware dalla cuffia
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            handler.removeCallbacks(commitMultiPressRunnable)
            pendingPressCount = 0
            playFeedbackBeep(presses = 2)
            onMarkerTriggered(2, keyCode, 2, "$keyName ($keyCode) [2x Incrocio HW]")
            return
        }

        // D34: 88 (KEYCODE_MEDIA_PREVIOUS) = 3 tocchi tradotti in hardware dalla cuffia
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            handler.removeCallbacks(commitMultiPressRunnable)
            pendingPressCount = 0
            playFeedbackBeep(presses = 3)
            onMarkerTriggered(4, keyCode, 3, "$keyName ($keyCode) [3x Previous HW]")
            return
        }

        // D34: 79 (HEADSETHOOK), 85 (PLAY_PAUSE), 126 (PLAY), 127 (PAUSE) = pressione singola o multipla
        val isSingleTapKey = keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE

        if (isSingleTapKey) {
            lastRawKeyCode = keyCode
            pendingPressCount++
            handler.removeCallbacks(commitMultiPressRunnable)
            if (pendingPressCount >= 3) {
                commitMultiPressRunnable.run()
            } else {
                handler.postDelayed(commitMultiPressRunnable, 450L)
            }
            return
        }

        // Qualsiasi altro keycode imprevisto viene registrato immediatamente
        playFeedbackBeep(presses = 1)
        onMarkerTriggered(8, keyCode, 1, "$keyName ($keyCode) [Altro Tasto]")
    }

    private fun playFeedbackBeep(presses: Int) {
        try {
            val tg = toneGenerator ?: return
            when (presses) {
                1 -> tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                2 -> tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                else -> tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 260)
            }
        } catch (_: Exception) {
        }
    }

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
