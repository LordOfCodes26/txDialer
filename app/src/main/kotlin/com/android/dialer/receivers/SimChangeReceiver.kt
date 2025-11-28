package com.android.dialer.receivers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import com.android.dialer.sim.SimInfoLoader

class SimChangeReceiver : BroadcastReceiver() {

    companion object {
        // AOSP string actions
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        private const val ACTION_SIM_CARD_STATE_CHANGED = "android.telephony.action.SIM_CARD_STATE_CHANGED"
        private const val ACTION_SIM_APPLICATION_STATE_CHANGED = "android.telephony.action.SIM_APPLICATION_STATE_CHANGED"
    }

    @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE])
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == ACTION_SIM_STATE_CHANGED ||
            action == ACTION_SIM_CARD_STATE_CHANGED ||
            action == ACTION_SIM_APPLICATION_STATE_CHANGED) {

            // Optional: detect slot index
            val slotIndex = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1)

            // Reload all SIM info
            SimInfoLoader.loadSimInfo(context)
        }
    }
}
