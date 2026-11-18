/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.position

import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.PhonePosition
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Risultato della classificazione di posizione dello smartphone (D21).
 */
data class PositionEstimate(
    val position: PhonePosition,
    val confidence: Float,
    val gyroVarianceRad2: Float,
    val horizontalGravityRatio: Float
)

/**
 * Classificatore automatico della posizione del telefono (`Braccio`, `Mano`, `Vita`, `Tasca` — D21).
 *
 * Utilizza l'orientamento medio del vettore gravità (da accelerometro su finestra di 2 s)
 * e la varianza angolare del giroscopio per distinguere:
 * - `WAIST` (Vita/Marsupio): bassa varianza rotazionale del bacino durante il passo.
 * - `POCKET` (Tasca): forte oscillazione pendolare su asse pitch/roll della coscia.
 * - `HAND` (Mano): maggiore componente di gravità sul piano orizzontale/inclinato e rotazione multi-asse.
 * - `ARM` (Braccio/Fascia): assetto verticale/laterale con oscillazione moderata.
 */
class PositionClassifier(
    private val config: KeepAppConfig = KeepAppConfig(),
    initialPositionHint: PhonePosition = PhonePosition.ARM
) {
    private data class AccelSample(val timestampNs: Long, val x: Float, val y: Float, val z: Float)
    private data class GyroSample(val timestampNs: Long, val x: Float, val y: Float, val z: Float)

    private val accelWindow = ArrayDeque<AccelSample>(256)
    private val gyroWindow = ArrayDeque<GyroSample>(256)

    private var currentPosition: PhonePosition = initialPositionHint
    private var currentConfidence: Float = 0.5f

    private val smoothedScores = FloatArray(PhonePosition.entries.size) { idx ->
        if (idx == initialPositionHint.index) 0.55f else 0.12f
    }

    fun setInitialHint(hint: PhonePosition) {
        currentPosition = hint
        for (i in smoothedScores.indices) {
            smoothedScores[i] = if (i == hint.index) 0.55f else 0.12f
        }
    }

    fun onAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        accelWindow.addLast(AccelSample(timestampNs, x, y, z))
        val cutoffNs = timestampNs - (config.positionWindowSeconds * 1_000_000_000f).toLong()
        while (accelWindow.isNotEmpty() && accelWindow.first().timestampNs < cutoffNs) {
            accelWindow.removeFirst()
        }
    }

    fun onGyroscope(timestampNs: Long, x: Float, y: Float, z: Float) {
        gyroWindow.addLast(GyroSample(timestampNs, x, y, z))
        val cutoffNs = timestampNs - (config.positionWindowSeconds * 1_000_000_000f).toLong()
        while (gyroWindow.isNotEmpty() && gyroWindow.first().timestampNs < cutoffNs) {
            gyroWindow.removeFirst()
        }
    }

    /**
     * Aggiorna e restituisce la stima corrente della posizione sulla finestra di 2 s.
     */
    fun evaluate(): PositionEstimate {
        if (accelWindow.size < 20) {
            return PositionEstimate(currentPosition, currentConfidence, 0f, 0f)
        }

        val nAcc = accelWindow.size.toFloat()
        var sumAx = 0f
        var sumAy = 0f
        var sumAz = 0f
        var sumMag = 0f
        var sumMagSq = 0f

        for (s in accelWindow) {
            sumAx += s.x
            sumAy += s.y
            sumAz += s.z
            val mag = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
            sumMag += mag
            sumMagSq += mag * mag
        }

        val meanAx = sumAx / nAcc
        val meanAy = sumAy / nAcc
        val meanAz = sumAz / nAcc
        val meanMag = sumMag / nAcc
        val magVar = (sumMagSq / nAcc - meanMag * meanMag).coerceAtLeast(0f)
        val dynamicStd = sqrt(magVar)

        val gNorm = sqrt(meanAx * meanAx + meanAy * meanAy + meanAz * meanAz).coerceAtLeast(0.1f)
        val gx = abs(meanAx) / gNorm
        val gy = abs(meanAy) / gNorm
        val gz = abs(meanAz) / gNorm
        val horizontalRatio = sqrt(gx * gx + gz * gz).coerceIn(0f, 1f)

        var gyroTotalVar = 0f
        var gyroPitchRollVar = 0f
        if (gyroWindow.size >= 10) {
            val nGyr = gyroWindow.size.toFloat()
            var mx = 0f
            var my = 0f
            var mz = 0f
            for (g in gyroWindow) {
                mx += g.x
                my += g.y
                mz += g.z
            }
            mx /= nGyr
            my /= nGyr
            mz /= nGyr

            var vx = 0f
            var vy = 0f
            var vz = 0f
            for (g in gyroWindow) {
                val dx = g.x - mx
                val dy = g.y - my
                val dz = g.z - mz
                vx += dx * dx
                vy += dy * dy
                vz += dz * dz
            }
            vx /= nGyr
            vy /= nGyr
            vz /= nGyr
            gyroTotalVar = vx + vy + vz
            gyroPitchRollVar = maxOf(vx, vy, vx + vz * 0.5f)
        }

        if (dynamicStd < 0.35f) {
            return PositionEstimate(
                position = currentPosition,
                confidence = currentConfidence,
                gyroVarianceRad2 = gyroTotalVar,
                horizontalGravityRatio = horizontalRatio
            )
        }

        val rawScores = FloatArray(PhonePosition.entries.size)

        // 1. WAIST (Vita): rotazione del bacino molto contenuta rispetto agli arti
        rawScores[PhonePosition.WAIST.index] = when {
            gyroWindow.size >= 10 && gyroTotalVar <= config.waistMaxGyroVarianceRad2 -> 0.85f
            gyroWindow.size >= 10 && gyroTotalVar <= config.waistMaxGyroVarianceRad2 * 1.4f -> 0.45f
            else -> 0.08f
        }

        // 2. POCKET (Tasca): forte oscillazione pendolare della coscia e telefono quasi verticale/inclinato
        rawScores[PhonePosition.POCKET.index] = when {
            gyroPitchRollVar >= config.pocketMinPitchGyroVarianceRad2 && gy >= 0.45f -> 0.85f
            gyroTotalVar >= config.pocketMinPitchGyroVarianceRad2 * 0.85f && gy >= 0.55f -> 0.55f
            else -> 0.10f
        }

        // 3. HAND (Mano): telefono più inclinato verso l'orizzontale (asse Z/X) + rotazione multi-asse
        rawScores[PhonePosition.HAND.index] = when {
            horizontalRatio >= config.handMinHorizontalGravityRatio && gz >= 0.48f -> 0.85f
            horizontalRatio >= config.handMinHorizontalGravityRatio && gyroTotalVar > config.waistMaxGyroVarianceRad2 -> 0.65f
            else -> 0.10f
        }

        // 4. ARM (Braccio/Fascia): assetto prevalentemente verticale/laterale (gy/gx alti) e gyro intermedio
        rawScores[PhonePosition.ARM.index] = when {
            gy >= 0.55f && gz < 0.48f && gyroTotalVar in (config.waistMaxGyroVarianceRad2 + 0.05f)..(config.pocketMinPitchGyroVarianceRad2 * 1.10f) -> 0.80f
            gy >= 0.50f && gyroWindow.size < 10 -> 0.55f
            else -> 0.15f
        }

        // 5. WRIST (Polso): ampia oscillazione pendolare dell'avambraccio e assetto misto
        rawScores[PhonePosition.WRIST.index] = when {
            gyroTotalVar >= config.pocketMinPitchGyroVarianceRad2 * 0.70f && gy < 0.55f -> 0.75f
            gyroTotalVar > config.waistMaxGyroVarianceRad2 && horizontalRatio >= 0.45f -> 0.45f
            else -> 0.12f
        }

        val sumRaw = rawScores.sum().coerceAtLeast(0.01f)
        val alpha = 0.65f
        var bestIdx = currentPosition.index
        var bestScore = -1f
        for (i in rawScores.indices) {
            val norm = rawScores[i] / sumRaw
            smoothedScores[i] = (1f - alpha) * smoothedScores[i] + alpha * norm
            if (smoothedScores[i] > bestScore) {
                bestScore = smoothedScores[i]
                bestIdx = i
            }
        }

        if (bestIdx != currentPosition.index && smoothedScores[bestIdx] >= smoothedScores[currentPosition.index] + 0.05f) {
            currentPosition = PhonePosition.fromIndex(bestIdx)
        }
        currentConfidence = smoothedScores[currentPosition.index].coerceIn(0f, 1f)

        return PositionEstimate(
            position = currentPosition,
            confidence = currentConfidence,
            gyroVarianceRad2 = gyroTotalVar,
            horizontalGravityRatio = horizontalRatio
        )
    }
}
