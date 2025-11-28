package com.android.dialer.services

import android.Manifest
import android.telecom.CallAudioState
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.config
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.extensions.powerManager
import com.android.dialer.helpers.*
import com.android.dialer.models.Events
import com.android.dialer.sim.SimStateManager
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        //val isScreenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        when {
            !powerManager.isInteractive /*|| isScreenLocked*/ -> {
                try {
                    startActivity(CallActivity.getStartIntent(this))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    // seems like startActivity can throw AndroidRuntimeException and ActivityNotFoundException, not yet sure when and why, lets show a notification
                    callNotificationManager.setupNotification()
                }
            }

            call.isOutgoing() -> {
                try {
                    startActivity(CallActivity.getStartIntent(this, needSelectSIM = call.details.accountHandle == null))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    // seems like startActivity can throw AndroidRuntimeException and ActivityNotFoundException, not yet sure when and why, lets show a notification
                    callNotificationManager.setupNotification()
                }
            }

            config.showIncomingCallsFullScreen /*&& getPhoneSize() < 2*/ -> {
                try {
                    startActivity(CallActivity.getStartIntent(this))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    // seems like startActivity can throw AndroidRuntimeException and ActivityNotFoundException, not yet sure when and why, lets show a notification
                    callNotificationManager.setupNotification()
                }
            }

            else -> callNotificationManager.setupNotification()
        }
        if (!call.isOutgoing() && !powerManager.isInteractive && config.flashForAlerts) MyCameraImpl.newInstance(this).toggleSOS()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)

        // get the slot index from accountHandle (do in service because we have Context)
        val slotIndex = getSlotIndexFromHandle(call.details.accountHandle)

        // compute minutes used (rounded up)
        val connectTime = call.details.connectTimeMillis
        val minutesUsed = if (connectTime > 0) {
            (((System.currentTimeMillis() - connectTime) + 59_999) / 60_000).toInt()
        } else 0

        if (slotIndex >= 0 && minutesUsed > 0) {
            SimStateManager.addUsedMinutes(slotIndex, minutesUsed)
            // optionally persist right away
            SimStateManager.saveAll(this)
        }

        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        EventBus.getDefault().post(Events.RefreshCallLog)

        if (CallManager.pendingRedialHandle == null) {
            if (CallManager.getPhoneState() == NoCall) {
                CallManager.inCallService = null
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
                if (wasPrimaryCall) {
                    startActivity(CallActivity.getStartIntent(this))
                }
            }
        } else {
            callNotificationManager.setupNotification()
        }

        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }



    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getSlotIndexFromHandle(handle: PhoneAccountHandle?): Int {
        if (handle == null) return -1
        val sm = getSystemService(SubscriptionManager::class.java) ?: return -1
        val list = sm.activeSubscriptionInfoList ?: return -1
        for (info in list) {
            // match by subscriptionId string, iccid or label - OEMs differ
            if (info.subscriptionId.toString() == handle.id || info.iccId == handle.id) {
                return info.simSlotIndex
            }
        }
        // fallback: try matching by carrier name
        for (info in list) {
            if (info.carrierName?.toString() == handle.id) return info.simSlotIndex
        }
        return -1
    }


    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }
}

