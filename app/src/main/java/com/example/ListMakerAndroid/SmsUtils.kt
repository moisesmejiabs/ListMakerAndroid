package com.example.listmakerandroid

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

object SmsUtils {

    /**
     * Ensure message only contains GSM 7-bit characters.
     * Unsupported characters (emoji, accents, etc.) are replaced with '?'.
     */
    private fun sanitizeMessage(input: String): String {
        val gsmChars = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ^{}\\[~]|€ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"+
                "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        val gsmSet = gsmChars.toSet()
        val sb = StringBuilder()

        for (c in input) {
            if (gsmSet.contains(c)) {
                sb.append(c)
            } else {
                sb.append('?') // replace unsupported char
            }
        }

        return sb.toString()
    }

    /**
     * Send SMS, preferring a given SIM slot if available.
     * Falls back to the system default SIM if no subscription info is exposed.
     * Automatically splits messages that exceed the SMS character limit.
     */
    fun sendSms(context: Context, phoneNumber: String, message: String, simSlotIndex: Int = 0) {
        val safeMessage = sanitizeMessage(message)

        Log.d("SmsUtils", "➡️ Preparing to send SMS")
        Log.d("SmsUtils", "   To: $phoneNumber")
        Log.d("SmsUtils", "   Message length: ${safeMessage.length}")
        Log.d("SmsUtils", "--- SMS BODY START ---\n$safeMessage\n--- SMS BODY END ---")
        Log.d("SmsUtils", "   Requested SIM slot: $simSlotIndex")

        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubs = subscriptionManager.activeSubscriptionInfoList

            val smsManager: SmsManager = if (!activeSubs.isNullOrEmpty() && simSlotIndex in activeSubs.indices) {
                val subInfo = activeSubs[simSlotIndex]
                val subId = subInfo.subscriptionId
                Log.d("SmsUtils", "📱 Using SIM[$simSlotIndex] (${subInfo.displayName}, number=${subInfo.number}, subId=$subId)")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                Log.w("SmsUtils", "⚠️ No active SIM subscriptions available, using default SMS manager")
                SmsManager.getDefault()
            }

            // 🔑 Split long messages automatically
            val parts = smsManager.divideMessage(safeMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                Log.d("SmsUtils", "✅ Sent multipart SMS with ${parts.size} parts, each length=${parts.map { it.length }}")
            } else {
                smsManager.sendTextMessage(phoneNumber, null, safeMessage, null, null)
                Log.d("SmsUtils", "✅ Sent single-part SMS")
            }

        } catch (e: Exception) {
            Log.e("SmsUtils", "❌ Failed to send SMS", e)
        }
    }
}
