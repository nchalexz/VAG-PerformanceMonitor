package io.github.nchalexz.aaperfmon.cockpit.ui

import android.os.SystemClock

/**
 * The ignition needle test runs once per app process, not on every return to the cockpit.
 *
 * The first gauge stamps the start; every gauge in that first cockpit joins the same 1.8 s sweep.
 */
object CockpitStartupSweep {
    const val DURATION_MS = 1800L

    private var startedAt = 0L

    @Synchronized
    fun start(): Long {
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        return startedAt
    }

    fun elapsedFraction(startedAt: Long): Float =
        ((SystemClock.elapsedRealtime() - startedAt) / DURATION_MS.toFloat()).coerceIn(0f, 1f)
}
