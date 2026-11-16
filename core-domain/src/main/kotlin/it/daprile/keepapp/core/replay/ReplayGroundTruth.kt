/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.replay

import it.daprile.keepapp.core.config.KeepAppConfig
import java.io.File
import java.security.MessageDigest

/**
 * Intervallo di riferimento definito in `ground_truth.json` (Step A2).
 */
enum class GroundTruthInterval {
    MARKERS,
    SESSION
}

/**
 * Intervallo reale di sosta dichiarato manualmente in `ground_truth.json` (in secondi dall'inizio).
 */
data class RealStopInterval(
    val startSeconds: Float,
    val endSeconds: Float
)

/**
 * Dati di verità a terra caricati da `ground_truth.json` (con precedenza) oppure da
 * `ground_truth_steps` in `manifest.json` (Step A2).
 */
data class GroundTruthData(
    val steps: Int?,
    val interval: GroundTruthInterval = GroundTruthInterval.SESSION,
    val position: String? = null,
    val activity: String? = null,
    val environment: String? = null,
    val realStops: List<RealStopInterval> = emptyList(),
    val note: String? = null,
    val source: String = "none"
)

/**
 * Carica `ground_truth.json` e `--config <file.json>` usando il parser JSON puro di `:core-domain`.
 */
object ReplayConfigAndTruthLoader {

    fun loadGroundTruth(sessionDir: File): GroundTruthData? {
        val gtFile = File(sessionDir, "ground_truth.json")
        if (gtFile.exists()) {
            val root = SimpleReplayJsonParser(gtFile.readText()).parseObject()
            val steps = (root["steps"] as? Number)?.toInt()
            val intervalStr = (root["interval"] as? String)?.lowercase() ?: "session"
            val interval = if (intervalStr == "markers") GroundTruthInterval.MARKERS else GroundTruthInterval.SESSION
            val realStops = ArrayList<RealStopInterval>()
            @Suppress("UNCHECKED_CAST")
            val rawStops = root["real_stops"] as? List<Any?> ?: emptyList()
            for (item in rawStops) {
                @Suppress("UNCHECKED_CAST")
                val pair = item as? List<Any?> ?: continue
                if (pair.size >= 2) {
                    val s = (pair[0] as? Number)?.toFloat() ?: continue
                    val e = (pair[1] as? Number)?.toFloat() ?: continue
                    realStops.add(RealStopInterval(s, e))
                }
            }
            return GroundTruthData(
                steps = steps,
                interval = interval,
                position = root["position"] as? String,
                activity = root["activity"] as? String,
                environment = root["environment"] as? String,
                realStops = realStops,
                note = root["note"] as? String,
                source = "ground_truth.json"
            )
        }

        val manifestFile = File(sessionDir, "manifest.json")
        if (manifestFile.exists()) {
            val root = SimpleReplayJsonParser(manifestFile.readText()).parseObject()
            val steps = (root["ground_truth_steps"] as? Number)?.toInt()
            if (steps != null) {
                return GroundTruthData(
                    steps = steps,
                    interval = GroundTruthInterval.SESSION,
                    source = "manifest.ground_truth_steps"
                )
            }
        }
        return null
    }

    fun applyConfigOverride(base: KeepAppConfig, configFile: File?): KeepAppConfig {
        if (configFile == null || !configFile.exists()) return base
        val map = SimpleReplayJsonParser(configFile.readText()).parseObject()
        fun flt(key: String, cur: Float): Float = (map[key] as? Number)?.toFloat() ?: cur
        fun intVal(key: String, cur: Int): Int = (map[key] as? Number)?.toInt() ?: cur
        fun lng(key: String, cur: Long): Long = (map[key] as? Number)?.toLong() ?: cur

        return base.copy(
            nominalAccelSampleRateHz = flt("nominalAccelSampleRateHz", base.nominalAccelSampleRateHz),
            cadenceHighPassCutoffHz = flt("cadenceHighPassCutoffHz", base.cadenceHighPassCutoffHz),
            cadenceLowPassCutoffHz = flt("cadenceLowPassCutoffHz", base.cadenceLowPassCutoffHz),
            cadenceWindowSeconds = flt("cadenceWindowSeconds", base.cadenceWindowSeconds),
            cadenceUpdateIntervalSeconds = flt("cadenceUpdateIntervalSeconds", base.cadenceUpdateIntervalSeconds),
            minCadenceSpm = flt("minCadenceSpm", base.minCadenceSpm),
            maxCadenceSpm = flt("maxCadenceSpm", base.maxCadenceSpm),
            minDynamicAccelRmsMps2 = flt("minDynamicAccelRmsMps2", base.minDynamicAccelRmsMps2),
            cadenceConfidenceThreshold = flt("cadenceConfidenceThreshold", base.cadenceConfidenceThreshold),
            cadenceSmoothingAlpha = flt("cadenceSmoothingAlpha", base.cadenceSmoothingAlpha),
            positionWindowSeconds = flt("positionWindowSeconds", base.positionWindowSeconds),
            waistMaxGyroVarianceRad2 = flt("waistMaxGyroVarianceRad2", base.waistMaxGyroVarianceRad2),
            pocketMinPitchGyroVarianceRad2 = flt("pocketMinPitchGyroVarianceRad2", base.pocketMinPitchGyroVarianceRad2),
            handMinHorizontalGravityRatio = flt("handMinHorizontalGravityRatio", base.handMinHorizontalGravityRatio),
            minImpactSpacingPeriodFraction = flt("minImpactSpacingPeriodFraction", base.minImpactSpacingPeriodFraction),
            phaseLockConfidenceThreshold = flt("phaseLockConfidenceThreshold", base.phaseLockConfidenceThreshold),
            maxPhaseCorrectionPerBeatMs = flt("maxPhaseCorrectionPerBeatMs", base.maxPhaseCorrectionPerBeatMs),
            phaseOffsetArmMs = flt("phaseOffsetArmMs", base.phaseOffsetArmMs),
            phaseOffsetHandMs = flt("phaseOffsetHandMs", base.phaseOffsetHandMs),
            phaseOffsetWaistMs = flt("phaseOffsetWaistMs", base.phaseOffsetWaistMs),
            phaseOffsetPocketMs = flt("phaseOffsetPocketMs", base.phaseOffsetPocketMs),
            anchorLockStableSeconds = flt("anchorLockStableSeconds", base.anchorLockStableSeconds),
            anchorLockMaxStdSpm = flt("anchorLockMaxStdSpm", base.anchorLockMaxStdSpm),
            anchorDropThresholdFraction = flt("anchorDropThresholdFraction", base.anchorDropThresholdFraction),
            anchorShortDropMaxSeconds = flt("anchorShortDropMaxSeconds", base.anchorShortDropMaxSeconds),
            anchorProlongedDropSeconds = flt("anchorProlongedDropSeconds", base.anchorProlongedDropSeconds),
            anchorMaxDecayBpmPer10s = flt("anchorMaxDecayBpmPer10s", base.anchorMaxDecayBpmPer10s),
            anchorMaxDeviationFraction = flt("anchorMaxDeviationFraction", base.anchorMaxDeviationFraction),
            stopMaxGpsSpeedMps = flt("stopMaxGpsSpeedMps", base.stopMaxGpsSpeedMps),
            stopNoStepTimeoutSeconds = flt("stopNoStepTimeoutSeconds", base.stopNoStepTimeoutSeconds),
            stopResumeConsecutiveSteps = intVal("stopResumeConsecutiveSteps", base.stopResumeConsecutiveSteps),
            stopAttenuationDb = flt("stopAttenuationDb", base.stopAttenuationDb),
            stopVolumeRampSeconds = flt("stopVolumeRampSeconds", base.stopVolumeRampSeconds),
            stopRelockThresholdSeconds = flt("stopRelockThresholdSeconds", base.stopRelockThresholdSeconds),
            headsetDoubleTapWindowMs = lng("headsetDoubleTapWindowMs", base.headsetDoubleTapWindowMs)
        )
    }

    fun configHashSha256(config: KeepAppConfig): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(config.toString().toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}

private class SimpleReplayJsonParser(private val src: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        skipWs()
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return map
        }
        while (pos < src.length) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> error("Carattere inatteso nel JSON alla posizione $pos")
            }
        }
        error("Oggetto JSON non chiuso")
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') {
            pos++
            return list
        }
        while (pos < src.length) {
            skipWs()
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> error("Carattere inatteso nell'array JSON alla posizione $pos")
            }
        }
        error("Array JSON non chiuso")
    }

    private fun parseValue(): Any? {
        skipWs()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                pos += 4
                true
            }
            'f' -> {
                pos += 5
                false
            }
            'n' -> {
                pos += 4
                null
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("Valore JSON non valido alla posizione $pos")
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\' && pos < src.length) {
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        error("Stringa JSON non chiusa")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val numStr = src.substring(start, pos)
        return if (isFloat) numStr.toDouble() else numStr.toLong()
    }

    private fun expect(ch: Char) {
        if (pos >= src.length || src[pos] != ch) error("Atteso '$ch' a $pos")
        pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun skipWs() {
        while (pos < src.length && (src[pos].isWhitespace() || src[pos] == '\uFEFF')) pos++
    }
}
