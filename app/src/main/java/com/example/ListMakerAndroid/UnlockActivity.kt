package com.example.ListMakerAndroid

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class UnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)




        val uniqueIdView = findViewById<TextView>(R.id.uniqueIdView)
        val codeInput = findViewById<EditText>(R.id.codeInput)
        val unlockBtn = findViewById<Button>(R.id.unlockButton)

        // Show the unique app ID on screen
        val uniqueId = UnlockManager.getUniqueAppId(this)
        uniqueIdView.text = "Unique App ID:\n$uniqueId"

        val copyButton = findViewById<Button>(R.id.copyButton)
        copyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AppID", uniqueId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Unique App ID copied!", Toast.LENGTH_SHORT).show()
        }

        unlockBtn.setOnClickListener {
            val code = codeInput.text.toString().trim()
            val success = UnlockManager.tryUnlockApp(this, code)

            if (success) {
                UnlockManager.markUnlockedForToday(this)

                Toast.makeText(this, "App unlocked for today!", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Invalid code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
