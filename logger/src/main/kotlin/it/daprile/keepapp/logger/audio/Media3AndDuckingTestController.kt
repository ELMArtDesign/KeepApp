/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stato UI per i test F7 (Media3 + DynamicsProcessing) e F8 (Ducking Spotify) della Milestone 4.
 */
data class Media3AndDuckingUiState(
    // F7 - Media3 Speed & Pitch
    val isMedia3Playing: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val audioSessionId: Int = 0,
    val processCpuPercent: Float = 0f,
    val userQualityRating: Int = 0, // 1..5
    // F7 - DynamicsProcessing Sidechain EQ (300 Hz - 3 kHz)
    val isSidechainActive: Boolean = false,
    val sidechainStatus: String = "Pronto (Premi il pulsante mentre Media3 suona)",
    val sidechainAudibleAnswer: String = "Non ancora valutato",
    // F8 - Spotify Ducking
    val isDuckingLoopActive: Boolean = false,
    val isSpeakingPromptNow: Boolean = false,
    val duckingCyclesCount: Int = 0,
    val secondsToNextDuck: Int = 20,
    val lastFocusResult: String = "-",
    val spotifyObservedBehavior: String = "Non ancora valutato"
)

/**
 * Controller per i test F7 (Media3 + Sidechain EQ DynamicsProcessing) e F8 (Ducking Spotify).
 *
 * Tutti gli asset audio sono generati matematicamente in locale (licenza Apache 2.0).
 */
class Media3AndDuckingTestController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var exoPlayer: ExoPlayer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    private var lastCpuTimeMs: Long = Process.getElapsedCpuTime()
    private var lastWallTimeMs: Long = SystemClock.elapsedRealtime()

    private val _uiState = MutableStateFlow(loadSavedEvaluations(Media3AndDuckingUiState()))
    val uiState: StateFlow<Media3AndDuckingUiState> = _uiState.asStateFlow()

    // Timer per il monitoraggio CPU quando Media3 è in riproduzione
    private val cpuMonitorRunnable = object : Runnable {
        override fun run() {
            if (!_uiState.value.isMedia3Playing) return
            val nowCpuMs = Process.getElapsedCpuTime()
            val nowWallMs = SystemClock.elapsedRealtime()
            val deltaCpu = nowCpuMs - lastCpuTimeMs
            val deltaWall = (nowWallMs - lastWallTimeMs).coerceAtLeast(1L)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            // Percentuale rispetto a un singolo core (e normalizzata)
            val cpuPct = ((deltaCpu.toFloat() / deltaWall.toFloat()) * 100f / cores).coerceIn(0f, 100f)

            lastCpuTimeMs = nowCpuMs
            lastWallTimeMs = nowWallMs

            _uiState.value = _uiState.value.copy(processCpuPercent = cpuPct)
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    // Timer per il ciclo automatico ogni 20 secondi del test F8 (Ducking Spotify)
    private val duckingCountdownRunnable = object : Runnable {
        override fun run() {
            val st = _uiState.value
            if (!st.isDuckingLoopActive) return

            if (st.secondsToNextDuck <= 1) {
                triggerSingleDuckingPrompt()
                _uiState.value = _uiState.value.copy(secondsToNextDuck = 20)
            } else {
                _uiState.value = _uiState.value.copy(secondsToNextDuck = st.secondsToNextDuck - 1)
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    // === F7: MEDIA3 TIME-STRETCH (0.85x - 1.15x) ===

    fun startMedia3Test(speed: Float = _uiState.value.playbackSpeed) {
        if (exoPlayer != null) {
            setPlaybackSpeed(speed)
            return
        }

        val testWavFile = ensureTestMusicWavGenerated()

        val player = ExoPlayer.Builder(context).build().apply {
            // Disattiva esplicitamente l'audio offload per garantire che DynamicsProcessing funzioni
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                        .build()
                )
                .build()

            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false
            )

            repeatMode = Player.REPEAT_MODE_ALL
            playbackParameters = PlaybackParameters(speed.coerceIn(0.85f, 1.15f), 1.0f)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(testWavFile)))
            prepare()
            play()
        }

        exoPlayer = player
        lastCpuTimeMs = Process.getElapsedCpuTime()
        lastWallTimeMs = SystemClock.elapsedRealtime()

        mainHandler.postDelayed({
            val sessionId = player.audioSessionId
            initDynamicsProcessingForSession(sessionId)
            _uiState.value = _uiState.value.copy(
                isMedia3Playing = true,
                playbackSpeed = speed,
                audioSessionId = sessionId
            )
            mainHandler.postDelayed(cpuMonitorRunnable, 1_000L)
        }, 250L)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.85f, 1.15f)
        exoPlayer?.playbackParameters = PlaybackParameters(clamped, 1.0f)
        _uiState.value = _uiState.value.copy(playbackSpeed = clamped)
    }

    fun stopMedia3Test() {
        mainHandler.removeCallbacks(cpuMonitorRunnable)
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (_: Exception) {
        }
        dynamicsProcessing = null

        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) {
        }
        exoPlayer = null

        _uiState.value = _uiState.value.copy(
            isMedia3Playing = false,
            isSidechainActive = false,
            processCpuPercent = 0f
        )
    }

    // === F7: TEST SIDECHAIN EQ CON DYNAMICSPROCESSING (300 Hz - 3 kHz) ===

    private fun initDynamicsProcessingForSession(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            _uiState.value = _uiState.value.copy(
                sidechainStatus = "SessionId non valido ($audioSessionId)"
            )
            return
        }

        try {
            dynamicsProcessing?.release()
            // Configura un PostEq a 8 bande per scavare con precisione chirurgica 300 Hz - 3 kHz
            val bandCount = 8
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ false, 0,
                /* mbcInUse = */ false, 0,
                /* postEqInUse = */ true, bandCount,
                /* limiterInUse = */ false
            ).build()

            val dp = DynamicsProcessing(0, audioSessionId, config)
            val cutoffFreqs = floatArrayOf(150f, 290f, 600f, 1200f, 2200f, 3100f, 5500f, 16000f)
            val eq = DynamicsProcessing.Eq(true, true, bandCount)
            for (b in 0 until bandCount) {
                eq.setBand(b, DynamicsProcessing.EqBand(true, cutoffFreqs[b], 0f))
            }
            dp.setPostEqAllChannelsTo(eq)
            dp.enabled = true
            dynamicsProcessing = dp

            _uiState.value = _uiState.value.copy(
                sidechainStatus = "OK: DynamicsProcessing agganciato (sessionId=$audioSessionId)"
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                sidechainStatus = "ERRORE DynamicsProcessing: ${e.javaClass.simpleName} - ${e.message}"
            )
        }
    }

    /**
     * Attenua le bande comprese tra 300 Hz e 3 kHz (bande 2, 3, 4, 5) fino a -12 dB
     * con una rampa di discesa di 200 ms, mantiene l'attenuazione per 3 secondi,
     * e risale a 0 dB con una rampa di 200 ms.
     */
    fun triggerSidechainEqEnvelopeTest() {
        val dp = dynamicsProcessing
        if (dp == null || !_uiState.value.isMedia3Playing) {
            _uiState.value = _uiState.value.copy(
                sidechainStatus = "Avvia prima la riproduzione Media3!"
            )
            return
        }

        if (_uiState.value.isSidechainActive) return

        Thread({
            try {
                _uiState.value = _uiState.value.copy(
                    isSidechainActive = true,
                    sidechainStatus = "🔽 Rampa discesa (200 ms) → Attenuazione 300Hz–3kHz (-12 dB)..."
                )

                val cutoffFreqs = floatArrayOf(150f, 290f, 600f, 1200f, 2200f, 3100f, 5500f, 16000f)
                val maxCutDb = -12.0f

                // 1. Rampa di attacco di 200 ms (10 step da 20 ms)
                for (step in 1..10) {
                    val gainDb = (maxCutDb * step) / 10f
                    applyMidBandGain(dp, cutoffFreqs, gainDb)
                    Thread.sleep(20L)
                }

                _uiState.value = _uiState.value.copy(
                    sidechainStatus = "🎙️ Sidechain EQ ATTIVO (-12 dB su 300 Hz – 3 kHz per 3,0 s)..."
                )

                // 2. Mantenimento per 3.000 ms
                Thread.sleep(3_000L)

                _uiState.value = _uiState.value.copy(
                    sidechainStatus = "🔼 Rampa rilascio (200 ms) → Ripristino 0 dB..."
                )

                // 3. Rampa di rilascio di 200 ms (10 step da 20 ms)
                for (step in 9 downTo 0) {
                    val gainDb = (maxCutDb * step) / 10f
                    applyMidBandGain(dp, cutoffFreqs, gainDb)
                    Thread.sleep(20L)
                }

                _uiState.value = _uiState.value.copy(
                    isSidechainActive = false,
                    sidechainStatus = "✅ Test Sidechain EQ 3s completato con successo (Zero errori)"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSidechainActive = false,
                    sidechainStatus = "❌ Errore durante rampa EQ: ${e.message}"
                )
            }
        }, "KeepApp-SidechainRamp").start()
    }

    private fun applyMidBandGain(
        dp: DynamicsProcessing,
        cutoffFreqs: FloatArray,
        midGainDb: Float
    ) {
        // Bande 2, 3, 4, 5 coprono esattamente la fascia 300 Hz .. 3.100 Hz;
        // Bande 0, 1 (< 290 Hz, bassi) e Bande 6, 7 (> 3.100 Hz, alti) restano a 0 dB!
        for (b in 2..5) {
            val band = DynamicsProcessing.EqBand(true, cutoffFreqs[b], midGainDb)
            dp.setPostEqBandAllChannelsTo(b, band)
        }
    }

    // === F8: TEST DUCKING DINAMICO CON SPOTIFY ===

    fun toggleDuckingLoop20s(enable: Boolean) {
        mainHandler.removeCallbacks(duckingCountdownRunnable)
        if (enable) {
            _uiState.value = _uiState.value.copy(
                isDuckingLoopActive = true,
                secondsToNextDuck = 5 // primo test dopo 5 secondi dall'attivazione
            )
            mainHandler.postDelayed(duckingCountdownRunnable, 1_000L)
        } else {
            _uiState.value = _uiState.value.copy(
                isDuckingLoopActive = false,
                secondsToNextDuck = 20
            )
        }
    }

    /**
     * Esegue un singolo intervento vocale di 3 secondi richiedendo:
     * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` + `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH`
     * e rilasciando immediatamente il focus al termine dei 3 secondi.
     */
    fun triggerSingleDuckingPrompt() {
        if (_uiState.value.isSpeakingPromptNow) return

        Thread({
            val focusAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(focusAttrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { }
                .build()

            // 1. Richiede il focus di ducking ~100 ms prima di parlare (D25)
            val res = audioManager.requestAudioFocus(focusRequest)
            val resStr = if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "GRANTED (Focus concesso)"
            } else {
                "FAILED ($res)"
            }

            val newCount = _uiState.value.duckingCyclesCount + 1
            _uiState.value = _uiState.value.copy(
                isSpeakingPromptNow = true,
                duckingCyclesCount = newCount,
                lastFocusResult = "Ciclo #$newCount: $resStr"
            )

            try {
                Thread.sleep(100L)
                // 2. Riproduce un segnale vocale sintetico (formanti a cadenza parlata) per 3,0 secondi
                playSyntheticVoicePrompt3Seconds(focusAttrs)
                Thread.sleep(100L)
            } catch (_: Exception) {
            } finally {
                // 3. Rilascia immediatamente l'AudioFocus così Spotify torna al 100% del volume
                audioManager.abandonAudioFocusRequest(focusRequest)
                _uiState.value = _uiState.value.copy(isSpeakingPromptNow = false)
            }
        }, "KeepApp-SpotifyDuckTest").start()
    }

    private fun playSyntheticVoicePrompt3Seconds(attrs: AudioAttributes) {
        val sampleRate = 24_000
        val totalSamples = sampleRate * 3 // 3,0 secondi esatti
        val pcm = ShortArray(totalSamples)

        // Sintetizza una sequenza tipo "ricetrasmittente / frase Coach" (squelch iniziale + modulazione sillabica)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Sillabe a 4 Hz (tipica cadenza del parlato italiano)
            val syllableEnv = 0.55 + 0.45 * sin(2.0 * PI * 4.2 * t)
            // Fondamentale vocale ~160 Hz + armoniche nella banda 500–1500 Hz
            val f0 = 155.0 + 25.0 * sin(2.0 * PI * 1.5 * t)
            val voice = 0.40 * sin(2.0 * PI * f0 * t) +
                    0.35 * sin(2.0 * PI * (f0 * 3.0) * t) +
                    0.20 * sin(2.0 * PI * (f0 * 6.0) * t)
            pcm[i] = (voice * syllableEnv * 22_000.0).toInt().coerceIn(-32000, 32000).toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(3_000L)
        track.stop()
        track.release()
    }

    // === Salvataggio valutazioni utente F7 e F8 ===

    fun recordMedia3QualityRating(stars: Int) {
        _uiState.value = _uiState.value.copy(userQualityRating = stars)
        persistEvaluations()
    }

    fun recordSidechainAudible(audibleLabel: String) {
        _uiState.value = _uiState.value.copy(sidechainAudibleAnswer = audibleLabel)
        persistEvaluations()
    }

    fun recordSpotifyDuckingBehavior(behaviorLabel: String) {
        _uiState.value = _uiState.value.copy(spotifyObservedBehavior = behaviorLabel)
        persistEvaluations()
    }

    fun releaseAll() {
        toggleDuckingLoop20s(false)
        stopMedia3Test()
    }

    private fun getEvalFile(): File = File(context.filesDir, "m4_audio_tests.json")

    private fun persistEvaluations() {
        try {
            val st = _uiState.value
            val json = JSONObject().apply {
                put("media3_quality_rating_1_to_5", st.userQualityRating)
                put("media3_last_speed", st.playbackSpeed.toDouble())
                put("media3_cpu_percent", st.processCpuPercent.toDouble())
                put("sidechain_status", st.sidechainStatus)
                put("sidechain_audible", st.sidechainAudibleAnswer)
                put("spotify_ducking_cycles", st.duckingCyclesCount)
                put("spotify_observed_behavior", st.spotifyObservedBehavior)
            }
            getEvalFile().writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun loadSavedEvaluations(defaultState: Media3AndDuckingUiState): Media3AndDuckingUiState {
        return try {
            val f = getEvalFile()
            if (!f.exists()) return defaultState
            val json = JSONObject(f.readText(Charsets.UTF_8))
            defaultState.copy(
                userQualityRating = json.optInt("media3_quality_rating_1_to_5", 0),
                sidechainAudibleAnswer = json.optString("sidechain_audible", "Non ancora valutato"),
                spotifyObservedBehavior = json.optString("spotify_observed_behavior", "Non ancora valutato")
            )
        } catch (_: Exception) {
            defaultState
        }
    }

    /**
     * Genera un file WAV musicale/ritmico di 8 secondi a 48.000 Hz stereo (Apache 2.0)
     * progettato specificamente per verificare:
     * 1) la tenuta del pitch (accordi musicali) e dei transienti ritmici tra 0.85x e 1.15x;
     * 2) l'attenuazione selettiva della banda 300 Hz – 3.000 Hz (DynamicsProcessing).
     */
    private fun ensureTestMusicWavGenerated(): File {
        val file = File(context.filesDir, "synthetic_test_track_48k.wav")
        if (file.exists() && file.length() > 100_000L) return file

        val sampleRate = 48_000
        val durationSec = 8
        val totalFrames = sampleRate * durationSec
        val dataBytes = totalFrames * 2 // mono 16-bit

        FileOutputStream(file, false).use { fos ->
            BufferedOutputStream(fos, 65_536).use { bos ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(dataBytes + 36)
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16)
                    putShort(1) // PCM
                    putShort(1) // Mono
                    putInt(sampleRate)
                    putInt(sampleRate * 2)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(dataBytes)
                }
                bos.write(header.array())

                val chunkFrames = 4_800
                val chunkBuf = ByteBuffer.allocate(chunkFrames * 2).order(ByteOrder.LITTLE_ENDIAN)

                // Progressione di 4 accordi a 120 BPM (ogni beat = 0.5 s)
                val bassFreqs = doubleArrayOf(110.0, 87.31, 130.81, 98.0)
                val midFreqs = doubleArrayOf(440.0, 523.25, 659.25, 783.99)

                var frameIdx = 0
                while (frameIdx < totalFrames) {
                    chunkBuf.clear()
                    for (i in 0 until chunkFrames) {
                        val t = (frameIdx + i).toDouble() / sampleRate
                        val chordIdx = ((t / 2.0).toInt()) % 4
                        val beatPhase = (t % 0.5) / 0.5 // 0..1 ad ogni battito da 120 BPM

                        // 1. Basso profondo (< 250 Hz, fuori dal taglio sidechain)
                        val bassEnv = (1.0 - beatPhase).coerceAtLeast(0.1)
                        val bass = 0.35 * sin(2.0 * PI * bassFreqs[chordIdx] * t) * bassEnv

                        // 2. Pad/Arpeggio nella banda centrale 300 Hz - 3 kHz (bersaglio del Sidechain EQ!)
                        val fMid = midFreqs[chordIdx]
                        val midPad = 0.22 * sin(2.0 * PI * fMid * t) +
                                0.18 * sin(2.0 * PI * (fMid * 1.5) * t) +
                                0.14 * sin(2.0 * PI * (fMid * 2.5) * t)

                        // 3. Impulso ritmico ad alta frequenza (4.800 Hz, fuori dal taglio sidechain)
                        val hiHatEnv = if (beatPhase < 0.06) (1.0 - beatPhase / 0.06) else 0.0
                        val hiHat = 0.20 * sin(2.0 * PI * 4800.0 * t) * cos(2.0 * PI * 710.0 * t) * hiHatEnv

                        val sample = ((bass + midPad + hiHat) * 26_000.0).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        chunkBuf.putShort(sample)
                    }
                    bos.write(chunkBuf.array())
                    frameIdx += chunkFrames
                }
                bos.flush()
            }
        }
        return file
    }
}
