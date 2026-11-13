package com.nuvio.app.features.analyzeddetails

/** Hybrid logical clock. A wall more than 24 hours ahead of the device is ignored. */
internal const val MAX_HLC_COUNTER: Int = 1_000_000
internal const val MAX_HLC_SKEW_MS: Long = 24L * 60L * 60L * 1000L
internal const val TOMBSTONE_KEEP_MS: Long = 90L * 24L * 60L * 60L * 1000L

private val DEVICE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

internal data class DeviceClock(val wallMs: Long, val counter: Int)

internal data class ClockStamp(val wallMs: Long, val counter: Int, val deviceId: String)

internal class SyncClockException(val code: String) : Exception(code)

internal fun clockIsAcceptable(wallMs: Long, nowMs: Long): Boolean =
    wallMs >= 0L && nowMs >= 0L && wallMs <= nowMs + MAX_HLC_SKEW_MS

internal fun tick(local: DeviceClock, nowMs: Long, deviceId: String): ClockStamp {
    if (nowMs < 0L) throw SyncClockException("bad_input")
    if (local.wallMs < 0L || local.counter < 0) throw SyncClockException("bad_input")
    if (!DEVICE_ID.matches(deviceId)) throw SyncClockException("bad_input")
    val wallMs: Long
    val counter: Int
    if (nowMs > local.wallMs || local.wallMs > nowMs + MAX_HLC_SKEW_MS) {
        wallMs = nowMs
        counter = 0
    } else {
        wallMs = local.wallMs
        counter = local.counter + 1
    }
    if (counter > MAX_HLC_COUNTER) throw SyncClockException("counter_exhausted")
    return ClockStamp(wallMs, counter, deviceId)
}

internal fun receive(local: DeviceClock, remote: ClockStamp, nowMs: Long): DeviceClock {
    if (!clockIsAcceptable(remote.wallMs, nowMs)) return local
    if (remote.counter < 0 || remote.counter > MAX_HLC_COUNTER) return local
    if (remote.wallMs > local.wallMs || (remote.wallMs == local.wallMs && remote.counter > local.counter)) {
        return DeviceClock(remote.wallMs, remote.counter)
    }
    return local
}

internal fun compareHlc(left: ClockStamp, right: ClockStamp): Int {
    if (left.wallMs != right.wallMs) return left.wallMs.compareTo(right.wallMs)
    if (left.counter != right.counter) return left.counter.compareTo(right.counter)
    if (left.deviceId != right.deviceId) return left.deviceId.compareTo(right.deviceId)
    return 0
}
