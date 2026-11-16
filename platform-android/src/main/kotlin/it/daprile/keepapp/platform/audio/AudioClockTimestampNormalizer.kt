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

import android.os.SystemClock
import kotlin.math.abs

/**
 * Helper Android per normalizzare i timestamp HAL di `AudioTrack.getTimestamp()` nella base `TIMEBASE_BOOTTIME`.
 */
object AudioClockTimestampNormalizer {
    fun normalizeTrackTimestampToBoottimeNs(trackNanoTimeNs: Long): Long {
        val nowMono = System.nanoTime()
        val nowBoot = SystemClock.elapsedRealtimeNanos()
        val diffFromMono = abs(trackNanoTimeNs - nowMono)
        val diffFromBoot = abs(trackNanoTimeNs - nowBoot)
        return if (diffFromMono < diffFromBoot) {
            trackNanoTimeNs + (nowBoot - nowMono)
        } else {
            trackNanoTimeNs
        }
    }
}
