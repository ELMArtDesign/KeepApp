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
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sintetizzatore vocale discreto basato su Android `TextToSpeech` (offline, Italiano — Fase 1B Step 2).
 *
 * Notifica `onSpeakingChanged(true/false)` così `KeepAppAudioEngine` può abbassare morbidamente
 * il volume del ritmo durante la frase vocale e rialzarlo subito dopo.
 */
class VoiceCoach(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private val utteranceCounter = AtomicInteger(0)

    @Volatile
    private var ready = false

    @Volatile
    var enabled: Boolean = true

    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val res = tts.setLanguage(Locale.ITALIAN)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.setSpeechRate(1.06f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                onSpeakingChanged(false)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeakingChanged(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeakingChanged(false)
            }
        })
        ready = true
    }

    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        val id = "keepapp_utt_${utteranceCounter.incrementAndGet()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        ready = false
        onSpeakingChanged(false)
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }
}
