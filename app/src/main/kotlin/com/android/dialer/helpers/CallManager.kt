package com.android.dialer.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.android.dialer.extensions.getStateCompat
import com.android.dialer.extensions.hasCapability
import com.android.dialer.extensions.isConference
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null

        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()

        private var lastOutgoingHandle: Uri? = null
        var pendingRedialHandle: Uri? = null

        // -------- Auto-redial support --------
        var enableAutoRedial: Boolean = false
        private var autoRedialAttempts = 0
        private val maxAutoRedialAttempts = 3
        private var userHungUp = false

        /** Called when a call is added */
        fun onCallAdded(call: Call) {
            this.call = call
            calls.add(call)

            if (call.isOutgoing()) {
                lastOutgoingHandle = call.details.handle
                userHungUp = false
                autoRedialAttempts = 0
            }

            for (listener in listeners) listener.onPrimaryCallChanged(call)

            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) { updateState() }
                override fun onDetailsChanged(call: Call, details: Call.Details) { updateState() }
                override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) { updateState() }
            })
        }

        /** Called when a call is removed */
        fun onCallRemoved(call: Call) {
            val wasPrimaryCall = call == getPrimaryCall()
            calls.remove(call)

            // Auto-redial logic
            if (enableAutoRedial && !userHungUp && call.isOutgoing() && autoRedialAttempts < maxAutoRedialAttempts) {
                autoRedialAttempts++
                val handle = call.details.handle ?: lastOutgoingHandle
                handle?.let { placeCall(it) }
                return
            }

            // Pending redial
            if (pendingRedialHandle != null && call.details.handle == pendingRedialHandle) {
                placeCall(pendingRedialHandle!!)
                pendingRedialHandle = null
            }

            updateState()
            for (listener in listeners) listener.onStateChanged()
        }

        /** Mark that the user manually hung up or rejected a call */
        fun markUserHungUp() {
            userHungUp = true
        }

        /** Redial the current outgoing call or last outgoing number */
        fun redial() {
            val outgoingCall = calls.find {
                it.getStateCompat() == Call.STATE_DIALING || it.getStateCompat() == Call.STATE_CONNECTING
            }
            val handle = outgoingCall?.details?.handle ?: lastOutgoingHandle ?: return

            if (outgoingCall != null) {
                pendingRedialHandle = handle
                outgoingCall.disconnect()
            } else {
                placeCall(handle)
            }
        }

        /** Helper: place a new outgoing call */
        private fun placeCall(handle: Uri) {
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = handle
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                inCallService?.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        fun getPhoneState(): PhoneState {
            return when (calls.size) {
                0 -> NoCall
                1 -> SingleCall(calls.first())
                2 -> {
                    val active = calls.find { it.getStateCompat() == Call.STATE_ACTIVE }
                    val newCall = calls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
                    val onHold = calls.find { it.getStateCompat() == Call.STATE_HOLDING }
                    if (active != null && newCall != null) TwoCalls(newCall, active)
                    else if (newCall != null && onHold != null) TwoCalls(newCall, onHold)
                    else if (active != null && onHold != null) TwoCalls(active, onHold)
                    else TwoCalls(calls[0], calls[1])
                }
                else -> {
                    val conference = calls.find { it.isConference() } ?: return NoCall
                    val secondCall = if (conference.children.size + 1 != calls.size) {
                        calls.filter { !it.isConference() }.subtract(conference.children.toSet()).firstOrNull()
                    } else null

                    if (secondCall == null) SingleCall(conference)
                    else {
                        val newCallState = secondCall.getStateCompat()
                        if (newCallState == Call.STATE_ACTIVE || newCallState == Call.STATE_CONNECTING || newCallState == Call.STATE_DIALING) {
                            TwoCalls(secondCall, conference)
                        } else TwoCalls(conference, secondCall)
                    }
                }
            }
        }

        fun getPhoneSize() = calls.size
        private fun getCallAudioState() = inCallService?.callAudioState
        fun getSupportedAudioRoutes(): Array<AudioRoute> = AudioRoute.entries.filter {
            val mask = getCallAudioState()?.supportedRouteMask
            mask != null && mask and it.route == it.route
        }.toTypedArray()
        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)
        fun setAudioRoute(newRoute: Int) { inCallService?.setAudioRoute(newRoute) }

        private fun updateState() {
            val primaryCall = when (val phoneState = getPhoneState()) {
                is NoCall -> null
                is SingleCall -> phoneState.call
                is TwoCalls -> phoneState.active
            }
            var notify = true
            if (primaryCall == null) call = null
            else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) listener.onPrimaryCallChanged(primaryCall)
                notify = false
            }
            if (notify) for (listener in listeners) listener.onStateChanged()
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall() = call
        fun getConferenceCalls() = calls.find { it.isConference() }?.children ?: emptyList()
        fun accept() { call?.answer(VideoProfile.STATE_AUDIO_ONLY) }
        fun reject(rejectWithMessage: Boolean = false, textMessage: String? = null) {
            if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) call!!.reject(rejectWithMessage, textMessage)
                else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) call!!.disconnect()
            }
        }
        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) call?.unhold() else call?.hold()
            return !isOnHold
        }
        fun swap() { if (calls.size > 1) calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold() }
        fun merge() {
            val conferenceableCalls = call!!.conferenceableCalls
            if (conferenceableCalls.isNotEmpty()) call!!.conference(conferenceableCalls.first())
            else if (call!!.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) call!!.mergeConference()
        }
        fun addListener(listener: CallManagerListener) { listeners.add(listener) }
        fun removeListener(listener: CallManagerListener) { listeners.remove(listener) }
        fun getState() = getPrimaryCall()?.getStateCompat()
        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({ call?.stopDtmfTone() }, DIALPAD_TONE_LENGTH_MS)
        }
    }
}

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)
}

sealed class PhoneState
data object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()
