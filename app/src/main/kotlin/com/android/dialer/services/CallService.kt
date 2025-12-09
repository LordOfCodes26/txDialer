package com.android.dialer.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.config
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.extensions.powerManager
import com.android.dialer.helpers.*
import com.android.dialer.models.Events
import com.squareup.seismic.ShakeDetector
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {

    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private var shakeDetector: ShakeDetector? = null
    private var isNear = false
    private var proximityListener: android.hardware.SensorEventListener? = null

    override fun onCreate() {
        super.onCreate()
        // Pass auto-redial config to CallManager
        CallManager.enableAutoRedial = config.enableAutoRedial
    }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
                stopShakeDetector()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        // Register call in CallManager
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // Start shake detector if incoming and shake-to-answer enabled
        if (config.enableShakeToAnswer &&
            !call.isOutgoing() &&
            call.state == Call.STATE_RINGING &&
            !powerManager.isInteractive
        ) {
            startProximitySensor()
            startShakeDetector(call)
        }

        // Launch UI or show notification based on power state / config
        when {
            !powerManager.isInteractive -> {
                try {
                    startActivity(CallActivity.getStartIntent(this))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    callNotificationManager.setupNotification()
                }
            }
            call.isOutgoing() -> {
                try {
                    startActivity(CallActivity.getStartIntent(this, needSelectSIM = call.details.accountHandle == null))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    callNotificationManager.setupNotification()
                }
            }
            config.showIncomingCallsFullScreen -> {
                try {
                    startActivity(CallActivity.getStartIntent(this))
                    callNotificationManager.setupNotification(true)
                } catch (_: Exception) {
                    callNotificationManager.setupNotification()
                }
            }
            else -> callNotificationManager.setupNotification()
        }

        if (!call.isOutgoing() && !powerManager.isInteractive && config.flashForAlerts) {
            MyCameraImpl.newInstance(this).toggleSOS()
        }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)

        stopProximitySensor()
        stopShakeDetector()

        CallManager.onCallRemoved(call)
        EventBus.getDefault().post(Events.RefreshCallLog)

        // Clean up notifications if no call remains
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
        }

        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    /** Call this from CallActivity when user manually declines/hangs up */
    fun markUserHungUp() {
        CallManager.markUserHungUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { CallManager.onAudioStateChanged(it) }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getSlotIndexFromHandle(handle: PhoneAccountHandle?): Int {
        if (handle == null) return -1
        val sm = getSystemService(SubscriptionManager::class.java) ?: return -1
        val list = sm.activeSubscriptionInfoList ?: return -1
        for (info in list) {
            if (info.subscriptionId.toString() == handle.id || info.iccId == handle.id) return info.simSlotIndex
        }
        for (info in list) {
            if (info.carrierName?.toString() == handle.id) return info.simSlotIndex
        }
        return -1
    }

    private fun startShakeDetector(call: Call) {
        if (!config.enableShakeToAnswer) return

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector {
            if (!isNear) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                stopShakeDetector()
                stopProximitySensor()
            }
        }
        shakeDetector?.start(sm)
    }

    private fun stopShakeDetector() {
        shakeDetector?.stop()
        shakeDetector = null
    }

    private fun startProximitySensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximity = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) ?: return

        proximityListener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                event ?: return
                isNear = event.values[0] < proximity.maximumRange
            }
        }

        sm.registerListener(
            proximityListener,
            proximity,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun stopProximitySensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximityListener?.let { sm.unregisterListener(it) }
        proximityListener = null
        isNear = false
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
        stopProximitySensor()
        stopShakeDetector()
        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }
}
