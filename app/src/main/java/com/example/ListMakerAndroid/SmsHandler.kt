// SmsHandler.kt
package com.example.listmakerandroid

import android.content.Context
import com.example.listmakerandroid.SmsUtils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class SmsHandler(private val context: Context) {

    fun handleSms(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: "{}"
            val json = JSONObject(postData)

            val phoneNumber = json.optString("phone")
            val message = json.optString("message")

            if (phoneNumber.isNotEmpty() && message.isNotEmpty()) {
                SmsUtils.sendSms(context, phoneNumber, message)
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    """{"status":"ok","msg":"SMS sent"}"""
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","msg":"Missing phone or message"}"""
                )
            }
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"status":"error","msg":"${e.message}"}"""
            )
        }
    }
}
