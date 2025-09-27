package com.example.ListMakerAndroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class SimpleServerService : Service() {

    private lateinit var server: SimpleServer
    private val port = 53399
    private val channelId = "listmaker_server"

    override fun onCreate() {
        super.onCreate()
        server = SimpleServer(this, port)
        server.startServer()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try { server.stopServer() } catch (_: Throwable) {}
        super.onDestroy()
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                channelId, "ListMaker Server", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val url = "http://${getDeviceIp() ?: "127.0.0.1"}:$port/login"
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val piFlags =
            if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        val contentPI = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // replace with your app icon
            .setContentTitle("ListMaker server running")
            .setContentText(url)
            .setOngoing(true)
            .setContentIntent(contentPI)
            .build()

        startForeground(1001, notif)
    }

    private fun getDeviceIp(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifaces) {
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
            }
        }
        return null
    }
}
