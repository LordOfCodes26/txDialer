package com.goodwy.dialer.sim

import android.telephony.TelephonyManager

data class SimCardState(
    val slotIndex: Int,
    val carrierName: String,
    val phoneNumber: String,
    val simState: Int = TelephonyManager.SIM_STATE_UNKNOWN,

    // minute pools
    val freeMinutesTotal: Int = 0,
    val freeMinutesUsed: Int = 0,
    val overflowMinutesTotal: Int = 0,
    val overflowMinutesUsed: Int = 0,

    // billing cycle day (1..31), 1 = first of month by default
    val cycleResetDay: Int = 1
) {

    val freeMinutesRemaining: Int
        get() = (freeMinutesTotal - freeMinutesUsed).coerceAtLeast(0)

    val overflowMinutesRemaining: Int
        get() = (overflowMinutesTotal - overflowMinutesUsed).coerceAtLeast(0)

    val totalMinutesRemaining: Int
        get() = freeMinutesRemaining + overflowMinutesRemaining

    fun recalculateOverflowUsage(): SimCardState {
        var overflowUsed = this.overflowMinutesUsed
        val freeRemaining = (this.freeMinutesTotal - this.freeMinutesUsed).coerceAtLeast(0)
        val totalUsed = this.freeMinutesUsed + this.overflowMinutesUsed
        val overflowCapacity = (this.freeMinutesTotal + this.overflowMinutesTotal) - totalUsed

        if (overflowUsed > overflowCapacity) {
            overflowUsed = overflowCapacity.coerceAtLeast(0)
        }
        return this.copy(overflowMinutesUsed = overflowUsed)
    }
}
