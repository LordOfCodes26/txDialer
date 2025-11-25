package com.goodwy.dialer.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.goodwy.dialer.extensions.getStateCompat
import com.goodwy.dialer.extensions.hasCapability
import com.goodwy.dialer.extensions.isConference
import com.goodwy.dialer.extensions.isOutgoing
import com.goodwy.dialer.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

// inspired by https://github.com/Chooloo/call_manage
class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()
        // Store the last outgoing number
        private var lastOutgoingHandle: Uri? = null

        // Store a pending redial handle if disconnecting a call
        public var pendingRedialHandle: Uri? = null
        /** Called when a call is added */
        fun onCallAdded(call: Call) {
            this.call = call
            calls.add(call)

            // Track outgoing number
            if (call.isOutgoing()) {
                lastOutgoingHandle = call.details.handle
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
            if (pendingRedialHandle != null && call.details.handle == pendingRedialHandle) {
                // Previous outgoing call fully removed → place the new call
                placeCall(pendingRedialHandle!!)
                pendingRedialHandle = null
            }
            updateState()
            for (listener in listeners) listener.onStateChanged()
        }

        /** Redial the current outgoing call or last outgoing number */
        fun redial() {
            val outgoingCall = calls.find {
                it.getStateCompat() == Call.STATE_DIALING || it.getStateCompat() == Call.STATE_CONNECTING
            }

            val handle = outgoingCall?.details?.handle ?: lastOutgoingHandle ?: return

            if (outgoingCall != null) {
                // Disconnect current outgoing call and mark pending redial
                pendingRedialHandle = handle
                outgoingCall.disconnect()
            } else {
                // No call in progress → place the call immediately
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
                    if (active != null && newCall != null) {
                        TwoCalls(newCall, active)
                    } else if (newCall != null && onHold != null) {
                        TwoCalls(newCall, onHold)
                    } else if (active != null && onHold != null) {
                        TwoCalls(active, onHold)
                    } else {
                        TwoCalls(calls[0], calls[1])
                    }
                }

                else -> {
                    val conference = calls.find { it.isConference() } ?: return NoCall
                    val secondCall = if (conference.children.size + 1 != calls.size) {
                        calls.filter { !it.isConference() }
                            .subtract(conference.children.toSet())
                            .firstOrNull()
                    } else {
                        null
                    }
                    if (secondCall == null) {
                        SingleCall(conference)
                    } else {
                        val newCallState = secondCall.getStateCompat()
                        if (newCallState == Call.STATE_ACTIVE || newCallState == Call.STATE_CONNECTING || newCallState == Call.STATE_DIALING) {
                            TwoCalls(secondCall, conference)
                        } else {
                            TwoCalls(conference, secondCall)
                        }
                    }
                }
            }
        }

        fun getPhoneSize(): Int {
            return calls.size
        }

        private fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.entries.filter {
                val supportedRouteMask = getCallAudioState()?.supportedRouteMask
                if (supportedRouteMask != null) {
                    supportedRouteMask and it.route == it.route
                } else {
                    false
                }
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)

        fun setAudioRoute(newRoute: Int) {
            inCallService?.setAudioRoute(newRoute)
        }

        private fun updateState() {
            val primaryCall = when (val phoneState = getPhoneState()) {
                is NoCall -> null
                is SingleCall -> phoneState.call
                is TwoCalls -> phoneState.active
            }
            var notify = true
            if (primaryCall == null) {
                call = null
            } else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) {
                    listener.onPrimaryCallChanged(primaryCall)
                }
                notify = false
            }
            if (notify) {
                for (listener in listeners) {
                    listener.onStateChanged()
                }
            }

            // remove all disconnected calls manually in case they are still here
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall(): Call? {
            return call
        }

        fun getConferenceCalls(): List<Call> {
            return calls.find { it.isConference() }?.children ?: emptyList()
        }

        fun accept() {
            call?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun reject(rejectWithMessage: Boolean = false, textMessage: String? = null) {
            if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    call!!.reject(rejectWithMessage, textMessage)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    call!!.disconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) {
                call?.unhold()
            } else {
                call?.hold()
            }
            return !isOnHold
        }

        fun swap() {
            if (calls.size > 1) {
                calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold()
            }
        }

        fun merge() {
            val conferenceableCalls = call!!.conferenceableCalls
            if (conferenceableCalls.isNotEmpty()) {
                call!!.conference(conferenceableCalls.first())
            } else {
                if (call!!.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
                    call!!.mergeConference()
                }
            }
        }

        fun addListener(listener: CallManagerListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallManagerListener) {
            listeners.remove(listener)
        }

        fun getState() = getPrimaryCall()?.getStateCompat()

        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({
                call?.stopDtmfTone()
            }, DIALPAD_TONE_LENGTH_MS)
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

