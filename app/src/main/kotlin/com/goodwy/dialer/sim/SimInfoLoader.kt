package com.goodwy.dialer.sim

import android.Manifest
import android.content.Context
import android.os.Handler
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission

object SimInfoLoader {

    private val REG_MINUTES_FREE = "(?:free|remaining|balance)\\s*minutes[: ]+(\\d+)".toRegex(RegexOption.IGNORE_CASE)
    private val REG_MINUTES_OVERFLOW = "(?:bonus|overflow)\\s*minutes[: ]+(\\d+)".toRegex(RegexOption.IGNORE_CASE)
    private val REG_MINUTES_USED = "(?:used)\\s*minutes[: ]+(\\d+)".toRegex(RegexOption.IGNORE_CASE)


    /** Load saved sim state, then refresh via telephony + USSD */
    @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE])
    fun loadSimInfo(context: Context) {
        try {
            // load previously persisted data first
            SimStateManager.loadAll(context)
            // reset monthly usage if needed
            SimStateManager.resetIfNeeded(context)

            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // Clear in-memory but keep loaded defaults (we'll overwrite)
            // We will update entries below; don't completely wipe persisted defaults
            // SimStateManager.clear()

            val activeSubs: List<SubscriptionInfo> = subscriptionManager.activeSubscriptionInfoList ?: emptyList()

            for (sub in activeSubs) {
                val slotIndex = sub.simSlotIndex
                val carrierName = sub.carrierName?.toString() ?: "Unknown"
                val number = sub.number ?: ""
                val state = telephonyManager.getSimState(slotIndex)

                // Load saved or create new
                val saved = SimStateManager.getSimState(slotIndex)
                val simState = saved?.copy(
                    carrierName = carrierName,
                    phoneNumber = number,
                    simState = state
                ) ?: SimCardState(
                    slotIndex = slotIndex,
                    carrierName = carrierName,
                    phoneNumber = number,
                    simState = state,
                    freeMinutesTotal = 0,
                    freeMinutesUsed = 0,
                    overflowMinutesTotal = 0,
                    overflowMinutesUsed = 0
                )

                SimStateManager.updateSimState(slotIndex, simState)

                // Fetch USSD minutes asynchronously (if carrier mapping exists)
                loadSimMinutesFromUSSD(context, slotIndex, carrierName)
            }

            // persist current sim info (they may be updated by USSD callbacks later)
            SimStateManager.saveAll(context)
        } catch (e: Exception) {
            e.printStackTrace()
            // Do not show Toast, just log the error
        }
    }

    /** Fetch SIM minutes via USSD per carrier */
    @RequiresPermission(allOf = [Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE])
    private fun loadSimMinutesFromUSSD(context: Context, slotIndex: Int, carrierName: String) {
        try {
            val ussdCode = getUSSDCodeForCarrier(carrierName)
            if (ussdCode.isEmpty()) return

            val subscriptionId = getSubscriptionIdForSlot(context, slotIndex)
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return

            val telephonyManager = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .createForSubscriptionId(subscriptionId)

            telephonyManager.sendUssdRequest(
                ussdCode,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager?,
                        request: String?,
                        response: CharSequence?
                    ) {
                        val (free, used, overflow) = parseSimMinutes(response?.toString())
                        val sim = SimStateManager.getSimState(slotIndex) ?: return
                        SimStateManager.updateSimState(
                            slotIndex,
                            sim.copy(
                                freeMinutesTotal = free,
                                freeMinutesUsed = used,
                                overflowMinutesTotal = overflow,
                                overflowMinutesUsed = 0
                            )
                        )
                        SimStateManager.saveAll(context)
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager?,
                        request: String?,
                        failureCode: Int
                    ) {
                        // optional: handle failure
                    }
                },
                Handler()
            )
        } catch (_: Exception) {}
    }

    private fun getUSSDCodeForCarrier(carrierName: String): String {
        return when (carrierName.lowercase()) {
            "verizon" -> "*611#"
            "att" -> "*777#"
            "tmobile" -> "*123#"
            else -> "" // add mappings for your carriers
        }
    }

    private fun parseSimMinutes(ussdResponse: String?): Triple<Int, Int, Int> {
        if (ussdResponse.isNullOrEmpty()) return Triple(0, 0, 0)

        // NOTE: adapt regex to actual carrier USSD replies
        val free = Regex("(?i)free\\s*minutes[:\\s]+(\\d+)").find(ussdResponse)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val used = Regex("(?i)used\\s*minutes[:\\s]+(\\d+)").find(ussdResponse)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val overflow = Regex("(?i)overflow\\s*minutes\\s*(?:remaining)?:?\\s*(\\d+)").find(ussdResponse)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        return Triple(free, used, overflow)
    }

    /**
     * Parse carrier SMS containing minute info.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun parseCarrierMinuteSms(context: Context, smsBody: String, subscriptionId: Int) {

        val slotIndex = getSlotIndexBySubscriptionId(context, subscriptionId)
        if (slotIndex < 0) return

        val current = SimStateManager.getSimState(slotIndex) ?: return

        // Parse values from SMS using regex
        val freeTotal = REG_MINUTES_FREE.find(smsBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val overflowTotal = REG_MINUTES_OVERFLOW.find(smsBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val usedTotal = REG_MINUTES_USED.find(smsBody)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Build new SIM state, keeping old values when SMS does NOT contain updates
        val updatedSim = current.copy(
            freeMinutesTotal = freeTotal ?: current.freeMinutesTotal,
            overflowMinutesTotal = overflowTotal ?: current.overflowMinutesTotal,
            freeMinutesUsed = usedTotal ?: current.freeMinutesUsed
        ).recalculateOverflowUsage()

        // Save in SimStateManager
        SimStateManager.updateSimState(slotIndex, updatedSim)
    }



    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getSubscriptionIdForSlot(context: Context, slotIndex: Int): Int {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeSub = subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == slotIndex }
        return activeSub?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * map subscriptionId -> slotIndex
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getSlotIndexBySubscriptionId(context: Context, subId: Int): Int {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return -1

        val sm = context.getSystemService(SubscriptionManager::class.java)
            ?: return -1

        val list = sm.activeSubscriptionInfoList ?: return -1

        // Match subscriptionId first (most reliable)
        val match = list.firstOrNull { it.subscriptionId == subId }
        return match?.simSlotIndex ?: -1
    }

}
