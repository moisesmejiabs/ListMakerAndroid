package com.example.ListMakerAndroid

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest
import java.util.*

object UnlockManager {

    private const val PREF_NAME = "unlock_prefs"
    private const val KEY_INSTALL_ID = "install_id"
    private const val SECRET_KEY = "MySuperSecret2025"
    private const val KEY_LAST_UNLOCK_SLICE = "last_unlock_slice"


    // Generate (or reuse) install ID (UUID per app install)
    private fun getOrCreateInstallId(context: Context): String {
        val prefs = getPrefs(context)
        var id = prefs.getString(KEY_INSTALL_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        }
        return id
    }

    // Collect the full unique ID: package + deviceId + installId
    fun getUniqueAppId(context: Context): String {
        val packageName = context.packageName
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknownDevice"
        val installId = getOrCreateInstallId(context)
        return "$packageName-$deviceId-$installId"
    }

    // Generate today’s OTC (valid for 24 hours)
    private fun generateTodayCode(context: Context): String {
        val uniqueId = getUniqueAppId(context)
        val timeSlice = System.currentTimeMillis() / (1000 * 60 * 60 * 24) // daily slice
        val raw = "$SECRET_KEY-$uniqueId-$timeSlice"
        val hash = sha256(raw)
        return hash.substring(0, 6).uppercase(Locale.US)
    }

    // Validate entered code
    fun tryUnlockApp(context: Context, inputCode: String): Boolean {
        val expectedCode = generateTodayCode(context)
        return inputCode.equals(expectedCode, ignoreCase = true)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // Mark the app as unlocked for today
    fun markUnlockedForToday(context: Context) {
        val prefs = getPrefs(context)
        val timeSlice = System.currentTimeMillis() / (1000 * 60 * 60 * 24)
        prefs.edit().putLong(KEY_LAST_UNLOCK_SLICE, timeSlice).apply()
    }

    // Check if the app is already unlocked for today's slice
    fun isUnlockedForToday(context: Context): Boolean {
        val prefs = getPrefs(context)
        val timeSlice = System.currentTimeMillis() / (1000 * 60 * 60 * 24)
        return prefs.getLong(KEY_LAST_UNLOCK_SLICE, -1L) == timeSlice
    }
}
