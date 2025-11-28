package com.android.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.audioManager
import com.android.dialer.extensions.config
import com.android.dialer.helpers.ACCEPT_CALL
import com.android.dialer.helpers.CallManager
import com.android.dialer.helpers.DECLINE_CALL
import com.android.dialer.helpers.MICROPHONE_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                if (!context.config.keepCallsInPopUp) context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()
            MICROPHONE_CALL -> {
                val isMicrophoneMute = context.audioManager.isMicrophoneMute
                CallManager.inCallService?.setMuted(!isMicrophoneMute)
            }
        }
    }
}
