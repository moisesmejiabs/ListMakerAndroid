package com.example.ListMakerAndroid

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button

class MainActivity : AppCompatActivity() {

    //private lateinit var server: SimpleServer
    private val port = 53399
    private val REQ_NOTIF = 42   // <- add this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the HTTP server via a Foreground Service so it survives in background
        maybeRequestNotifPermission {
            val svc = Intent(this, SimpleServerService::class.java)
            ContextCompat.startForegroundService(this, svc)
        }

        val info = findViewById<TextView>(R.id.info)
        val qrView = findViewById<ImageView>(R.id.qr)

        //server = SimpleServer(this, port)
        //server.startServer()

        val ip = getDeviceIp() ?: "0.0.0.0"
        val url = "http://$ip:$port/login.html"

        info.text = """
            Server running.

            Connect from other devices:
            $url
        """.trimIndent()

        qrView.setImageBitmap(makeQr(url, 800, 800))
        // NEW: open the login page in the default browser
        findViewById<Button>(R.id.openInBrowser).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //try { server.stopServer() } catch (_: Throwable) {}
    }

    /**
     * First non-loopback IPv4.
     */
    private fun getDeviceIp(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifaces) {
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) {
                    return a.hostAddress
                }
            }
        }
        return null
    }

    private fun makeQr(text: String, width: Int, height: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    private fun maybeRequestNotifPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
                return
            }
        }
        onGranted()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val svc = Intent(this, SimpleServerService::class.java)
            ContextCompat.startForegroundService(this, svc)
        }
    }



}
