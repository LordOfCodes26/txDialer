package com.android.dialer.receivers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.annotation.RequiresPermission
import com.android.dialer.sim.SimInfoLoader

class SMSReceiver : BroadcastReceiver() {

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val smsBody = StringBuilder()
        for (msg in messages) smsBody.append(msg.displayMessageBody)

        val subscriptionId = intent.extras?.getInt("subscription", -1)
            ?.takeIf { it != -1 } ?: intent.extras?.getInt("subId", -1)?.takeIf { it != -1 }
        ?: return

        try {
            SimInfoLoader.parseCarrierMinuteSms(context, smsBody.toString(), subscriptionId)
        } catch (_: Exception) {
        }
    }
}
