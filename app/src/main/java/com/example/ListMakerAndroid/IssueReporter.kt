package com.example.ListMakerAndroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * IssueReporter — reusable voice-based issue reporter for Android.
 *
 * Usage:
 *   val launcher = IssueReporter.register(this, formUrl, entryId)
 *   IssueReporter.startVoiceReport(this, launcher)
 */
object IssueReporter {

    private const val TAG = "IssueReporter"

    /**
     * Register a launcher for speech recognition.
     * Must be called inside a ComponentActivity (AppCompatActivity is fine).
     */
    fun register(
        activity: ComponentActivity,
        googleFormUrl: String,
        formEntryId: String
    ): ActivityResultLauncher<Intent> {

        Log.d(TAG, "🧩 Registering IssueReporter launcher for form=$googleFormUrl entry=$formEntryId")

        // registerForActivityResult is available only from ComponentActivity
        return activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: androidx.activity.result.ActivityResult ->
            Log.d(TAG, "🎤 Voice input resultCode=${result.resultCode}")

            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()

                Log.d(TAG, "🗣️ Recognized text: $spokenText")

                if (!spokenText.isNullOrBlank()) {
                    try {
                        val encoded = URLEncoder.encode(spokenText, StandardCharsets.UTF_8.toString())
                        val fullUrl = "$googleFormUrl?usp=pp_url&entry.$formEntryId=$encoded"
                        Log.d(TAG, "🌐 Launching browser for URL: $fullUrl")
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                        activity.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error launching form", e)
                        Toast.makeText(activity, "Failed to open form.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w(TAG, "⚠️ No speech recognized.")
                    Toast.makeText(activity, "No speech detected.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.i(TAG, "ℹ️ Voice input canceled by user.")
                Toast.makeText(activity, "Voice input canceled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Launches Android’s voice recognition prompt.
     */
    fun startVoiceReport(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        Log.d(TAG, "🎬 Starting voice report input...")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the issue you encountered")
        }

        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start speech recognizer", e)
            Toast.makeText(activity, "Speech input not supported on this device.", Toast.LENGTH_LONG).show()
        }
    }
}
