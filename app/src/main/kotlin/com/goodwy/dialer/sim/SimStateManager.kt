package com.goodwy.dialer.sim

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

object SimStateManager {

    private val _sims = ConcurrentHashMap<Int, SimCardState>()
    val sims: Map<Int, SimCardState> get() = _sims

    private const val PREFS = "sim_state_prefs"
    private const val KEY_JSON = "sim_state_json"
    private const val KEY_LAST_RESET = "sim_state_last_reset" // yyyy-MM-dd

    fun clear() {
        _sims.clear()
    }

    fun updateSimState(slotIndex: Int, simState: SimCardState) {
        _sims[slotIndex] = simState
    }

    fun getSimState(slotIndex: Int): SimCardState? = _sims[slotIndex]

    fun getAllSims(): List<SimCardState> = _sims.values.toList()

    /**
     * Consume minutes for a finished call:
     * - use free minutes first
     * - then overflow minutes
     */
    fun addUsedMinutes(slotIndex: Int, minutesUsed: Int): Int {
        val sim = _sims[slotIndex] ?: return 0
        var remaining = minutesUsed
        var freeUsed = sim.freeMinutesUsed
        var overflowUsed = sim.overflowMinutesUsed

        // Consume free minutes first
        val freeRemaining = (sim.freeMinutesTotal - freeUsed).coerceAtLeast(0)
        val consumeFromFree = remaining.coerceAtMost(freeRemaining)
        freeUsed += consumeFromFree
        remaining -= consumeFromFree

        // Consume overflow minutes if needed
        if (remaining > 0) {
            val overflowRemaining = (sim.overflowMinutesTotal - overflowUsed).coerceAtLeast(0)
            val consumeOverflow = remaining.coerceAtMost(overflowRemaining)
            overflowUsed += consumeOverflow
            remaining -= consumeOverflow
        }

        // Update SIM state
        _sims[slotIndex] = sim.copy(
            freeMinutesUsed = freeUsed,
            overflowMinutesUsed = overflowUsed
        )

        // Return total minutes consumed (free + overflow)
        return minutesUsed - remaining
    }


    fun getTotalRemainingMinutes(slotIndex: Int): Int {
        val sim = _sims[slotIndex] ?: return 0
        return sim.totalMinutesRemaining
    }

    // -------------------------
    // Persistence (SharedPrefs)
    // -------------------------
    fun saveAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject()
        for ((slot, sim) in _sims) {
            val obj = JSONObject()
            obj.put("slotIndex", sim.slotIndex)
            obj.put("carrierName", sim.carrierName)
            obj.put("phoneNumber", sim.phoneNumber)
            obj.put("simState", sim.simState)
            obj.put("freeMinutesTotal", sim.freeMinutesTotal)
            obj.put("freeMinutesUsed", sim.freeMinutesUsed)
            obj.put("overflowMinutesTotal", sim.overflowMinutesTotal)
            obj.put("overflowMinutesUsed", sim.overflowMinutesUsed)
            obj.put("cycleResetDay", sim.cycleResetDay)
            json.put(slot.toString(), obj)
        }
        prefs.edit { putString(KEY_JSON, json.toString()) }
    }

    fun loadAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_JSON, null) ?: return
        try {
            val json = JSONObject(jsonStr)
            _sims.clear()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = json.getJSONObject(k)
                val slotIndex = obj.getInt("slotIndex")
                val sim = SimCardState(
                    slotIndex = slotIndex,
                    carrierName = obj.optString("carrierName", "Unknown"),
                    phoneNumber = obj.optString("phoneNumber", ""),
                    simState = obj.optInt("simState", -1),
                    freeMinutesTotal = obj.optInt("freeMinutesTotal", 0),
                    freeMinutesUsed = obj.optInt("freeMinutesUsed", 0),
                    overflowMinutesTotal = obj.optInt("overflowMinutesTotal", 0),
                    overflowMinutesUsed = obj.optInt("overflowMinutesUsed", 0),
                    cycleResetDay = obj.optInt("cycleResetDay", 1)
                )
                _sims[slotIndex] = sim
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Reset monthly usage when billing cycle day passes.
     * Call at app start and once per day (or on boot).
     */
    fun resetIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastReset = prefs.getString(KEY_LAST_RESET, null)
        val today = java.time.LocalDate.now().toString()
        if (lastReset == today) return

        // Reset for sims where cycleResetDay == today-day-of-month
        val dayOfMonth = java.time.LocalDate.now().dayOfMonth
        var changed = false
        for ((slot, sim) in _sims) {
            if (sim.cycleResetDay == dayOfMonth) {
                _sims[slot] = sim.copy(freeMinutesUsed = 0, overflowMinutesUsed = 0)
                changed = true
            }
        }
        if (changed) {
            prefs.edit().putString(KEY_LAST_RESET, today).apply()
            saveAll(context)
        } else {
            // still mark the last reset to avoid repeating the check multiple times today
            prefs.edit().putString(KEY_LAST_RESET, today).apply()
        }
    }
}
