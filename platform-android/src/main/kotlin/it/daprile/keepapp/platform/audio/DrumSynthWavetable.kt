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

import it.daprile.keepapp.core.rhythm.DrumInstrument
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Sintetizzatore offline di wavetable PCM a 48.000 Hz per strumenti percussivi (D41 — Apache 2.0,
 * zero campioni audio di terze parti).
 *
 * Ottimizzazione acustica per cuffie a conduzione ossea (`MANKIW-X15`):
 * - I trasduttori ossei attenuano fortemente le frequenze < 100 Hz.
 * - La cassa (`KICK`) usa quindi un pitch-sweep `240 -> 115 Hz` abbinato a un transiente
 *   d'attacco a `1.100 Hz` (0,9–1,4 kHz) per un colpo netto e percepibile senza distorsioni.
 */
object DrumSynthWavetable {

    const val SAMPLE_RATE_HZ = 48_000

    class Bank(
        val kick: FloatArray,
        val snare: FloatArray,
        val hihat: FloatArray,
        val accent: FloatArray,
        val metronomeClick: FloatArray
    ) {
        fun forInstrument(instrument: DrumInstrument): FloatArray = when (instrument) {
            DrumInstrument.KICK -> kick
            DrumInstrument.SNARE -> snare
            DrumInstrument.HIHAT -> hihat
            DrumInstrument.ACCENT -> accent
            DrumInstrument.METRONOME_CLICK -> metronomeClick
        }
    }

    fun buildDefaultBank(sampleRateHz: Int = SAMPLE_RATE_HZ): Bank = Bank(
        kick = synthesizeKick(sampleRateHz),
        snare = synthesizeSnare(sampleRateHz),
        hihat = synthesizeHiHat(sampleRateHz),
        accent = synthesizeAccent(sampleRateHz),
        metronomeClick = synthesizeMetronomeClick(sampleRateHz)
    )

    private fun synthesizeKick(sr: Int): FloatArray {
        val durationSec = 0.145
        val len = (sr * durationSec).toInt()
        val out = FloatArray(len)
        var phaseBody = 0.0
        var phaseClick = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            // Pitch sweep 240 Hz -> 115 Hz nei primi 45 ms
            val freqBody = 115.0 + 125.0 * exp(-t / 0.018)
            phaseBody += 2.0 * PI * freqBody / sr
            val envBody = exp(-t / 0.042) * fadeIn(i, 24)

            // Transiente d'attacco 1.1 kHz per chiarezza su conduzione ossea
            phaseClick += 2.0 * PI * 1120.0 / sr
            val envClick = exp(-t / 0.0035) * fadeIn(i, 8)

            val sample = 0.78 * sin(phaseBody) * envBody + 0.34 * sin(phaseClick) * envClick
            out[i] = (sample * fadeOutTail(i, len, 120)).toFloat()
        }
        return normalizePeak(out, 0.88f)
    }

    private fun synthesizeSnare(sr: Int): FloatArray {
        val durationSec = 0.135
        val len = (sr * durationSec).toInt()
        val out = FloatArray(len)
        var phaseTone = 0.0
        var rng = 0x5A17_93E1L
        var lpState = 0.0
        var hpState = 0.0
        var prevIn = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            phaseTone += 2.0 * PI * 210.0 / sr
            val tone = sin(phaseTone) * exp(-t / 0.022)

            // Rumore pseudo-casuale deterministico (LCG) + filtro passa-banda grezzo 1–4 kHz
            rng = rng * 6364136223846793005L + 1442695040888963407L
            val white = ((rng shr 33).toInt().toDouble() / Int.MAX_VALUE.toDouble())
            hpState = 0.85 * (hpState + white - prevIn)
            prevIn = white
            lpState = lpState + 0.38 * (hpState - lpState)

            val noiseEnv = exp(-t / 0.045)
            val sample = (0.48 * tone + 0.65 * lpState * noiseEnv) * fadeIn(i, 16) * fadeOutTail(i, len, 120)
            out[i] = sample.toFloat()
        }
        return normalizePeak(out, 0.82f)
    }

    private fun synthesizeHiHat(sr: Int): FloatArray {
        val durationSec = 0.058
        val len = (sr * durationSec).toInt()
        val out = FloatArray(len)
        var rng = 0x1B87_3593L
        var hp1 = 0.0
        var prev1 = 0.0
        var hp2 = 0.0
        var prev2 = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            rng = rng * 6364136223846793005L + 1442695040888963407L
            val white = ((rng shr 33).toInt().toDouble() / Int.MAX_VALUE.toDouble())
            // Doppio filtro passa-alto per brillantezza 4–9 kHz
            hp1 = 0.72 * (hp1 + white - prev1)
            prev1 = white
            hp2 = 0.72 * (hp2 + hp1 - prev2)
            prev2 = hp1

            val env = exp(-t / 0.015) * fadeIn(i, 10) * fadeOutTail(i, len, 80)
            out[i] = (hp2 * env).toFloat()
        }
        return normalizePeak(out, 0.68f)
    }

    private fun synthesizeAccent(sr: Int): FloatArray {
        val durationSec = 0.090
        val len = (sr * durationSec).toInt()
        val out = FloatArray(len)
        var p1 = 0.0
        var p2 = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            p1 += 2.0 * PI * 1240.0 / sr
            p2 += 2.0 * PI * 2480.0 / sr
            val env = exp(-t / 0.024) * fadeIn(i, 12) * fadeOutTail(i, len, 96)
            out[i] = ((0.72 * sin(p1) + 0.28 * sin(p2)) * env).toFloat()
        }
        return normalizePeak(out, 0.85f)
    }

    private fun synthesizeMetronomeClick(sr: Int): FloatArray {
        // Impulso da 12 ms a 1.000 Hz con finestra di Hann (nessun click spettrale)
        val len = (sr * 0.012).toInt()
        val out = FloatArray(len)
        for (i in 0 until len) {
            val t = i.toDouble() / sr
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (len - 1)))
            out[i] = (sin(2.0 * PI * 1000.0 * t) * hann).toFloat()
        }
        return normalizePeak(out, 0.85f)
    }

    private fun fadeIn(index: Int, fadeSamples: Int): Double {
        if (index >= fadeSamples) return 1.0
        return index.toDouble() / fadeSamples.toDouble()
    }

    private fun fadeOutTail(index: Int, totalLen: Int, tailSamples: Int): Double {
        val remaining = totalLen - 1 - index
        if (remaining >= tailSamples) return 1.0
        return max(0.0, remaining.toDouble() / tailSamples.toDouble())
    }

    private fun normalizePeak(samples: FloatArray, targetPeak: Float): FloatArray {
        var maxAbs = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs <= 1e-6f) return samples
        val scale = targetPeak / maxAbs
        for (i in samples.indices) {
            samples[i] *= scale
        }
        return samples
    }
}
