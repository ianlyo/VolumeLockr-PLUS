package com.lnu.volumelockr.plus.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope

class TvRemoteServer(private val context: Context, private val serviceScope: CoroutineScope) {
    companion object {
        private const val PREF_AUTH_TOKEN = "tv_auth_token"
        private const val PIN_EXPIRATION_MS = 60000L // 1 minute

        @Volatile
        private var currentPin: String? = null
        @Volatile
        private var pinTimestamp: Long = 0L

        fun getAuthToken(context: Context): String {
            val prefs = context.getSharedPreferences("tv_remote_auth", Context.MODE_PRIVATE)
            var token = prefs.getString(PREF_AUTH_TOKEN, null)
            if (token.isNullOrEmpty() || token.length < 16) {
                token = java.util.UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString(PREF_AUTH_TOKEN, token).apply()
            }
            return token
        }

        @Synchronized
        fun getOrGeneratePairingPin(): Pair<String, Int> {
            val now = System.currentTimeMillis()
            val elapsed = now - pinTimestamp
            if (currentPin == null || elapsed >= PIN_EXPIRATION_MS) {
                currentPin = String.format("%06d", (0..999999).random())
                pinTimestamp = now
            }
            val remainingSec = ((PIN_EXPIRATION_MS - (now - pinTimestamp)) / 1000).toInt().coerceAtLeast(0)
            return Pair(currentPin!!, remainingSec)
        }

        @Synchronized
        fun regeneratePairingPin() {
            currentPin = String.format("%06d", (0..999999).random())
            pinTimestamp = System.currentTimeMillis()
        }

        fun isValidPin(pin: String?): Boolean {
            if (pin.isNullOrEmpty() || currentPin == null) return false
            val elapsed = System.currentTimeMillis() - pinTimestamp
            return elapsed <= PIN_EXPIRATION_MS && pin == currentPin
        }
    }

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = serviceScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("0.0.0.0", 8080))
                }
                Log.d("TvRemoteServer", "Server started on port 8080")
                while (isActive) {
                    val client = serverSocket?.accept()
                    client?.let {
                        launch(Dispatchers.IO) { handleClient(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("TvRemoteServer", "Server error", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun notifyPairingSuccess() {
        val intent = Intent("com.lnu.volumelockr.plus.ACTION_PAIRED_SUCCESS")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            Log.d("TvRemoteServer", "Request: $requestLine")

            val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: ""
            val query = pathAndQuery.substringAfter("?", "")
            val params = parseQuery(query)

            val clientToken = params["token"] ?: params["pin"]
            val serverToken = getAuthToken(context)
            val isPinValid = isValidPin(clientToken)

            if (clientToken != serverToken && !isPinValid) {
                sendResponse(output, 401, "Unauthorized")
                return
            }

            if (isPinValid) {
                regeneratePairingPin()
                notifyPairingSuccess()
            }

            if (requestLine.startsWith("GET /ping")) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                
                // Check if icon is hidden
                val componentName = android.content.ComponentName(context, "com.lnu.volumelockr.plus.TvLauncherAlias")
                val state = context.packageManager.getComponentEnabledSetting(componentName)
                val hideIcon = state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                
                // Check if unlock is hidden
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val hideUnlock = prefs.getBoolean("hide_tv_unlock_ui", true)
                
                sendResponse(output, 200, "OK,$serverToken,$maxVolume,$currentVolume,$hideIcon,$hideUnlock")
            } else if (requestLine.startsWith("GET /set_lock")) {
                val stream = params["stream"]?.toIntOrNull()
                val volume = params["volume"]?.toIntOrNull()
                val locked = params["locked"]?.toBoolean()

                if (stream != null && locked != null) {
                    val intent = Intent(context, VolumeService::class.java).apply {
                        action = com.lnu.volumelockr.plus.util.AppConstants.ACTION_SET_LOCK
                        putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_STREAM, stream)
                        putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_LOCKED, locked)
                        if (volume != null) {
                            putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_VOLUME, volume)
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    sendResponse(output, 200, "OK")
                } else {
                    sendResponse(output, 400, "Bad Request")
                }
            } else if (requestLine.startsWith("GET /set_volume")) {
                val stream = params["stream"]?.toIntOrNull()
                val volume = params["volume"]?.toIntOrNull()

                if (stream != null && volume != null) {
                    try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.setStreamVolume(stream, volume, 0)

                        val intent = Intent(context, VolumeService::class.java).apply {
                            action = com.lnu.volumelockr.plus.util.AppConstants.ACTION_SET_LOCK
                            putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_STREAM, stream)
                            putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_VOLUME, volume)
                            putExtra(com.lnu.volumelockr.plus.util.AppConstants.EXTRA_UPDATE_IF_LOCKED_ONLY, true)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }

                        sendResponse(output, 200, "OK")
                    } catch (e: Exception) {
                        sendResponse(output, 500, "Internal Server Error")
                    }
                } else {
                    sendResponse(output, 400, "Bad Request")
                }
            } else if (requestLine.startsWith("GET /hide_icon")) {
                val hide = params["hide"]?.toBoolean() ?: true
                
                val state = if (hide) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(context, "com.lnu.volumelockr.plus.TvLauncherAlias"),
                    state,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                
                // Attempt to kill launcher background processes to force a cache refresh
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses("com.google.android.apps.tv.launcherx")
                    am.killBackgroundProcesses("com.google.android.tvlauncher")
                    am.killBackgroundProcesses("com.google.android.leanbacklauncher")
                } catch (e: Exception) {
                    Log.e("TvRemoteServer", "Failed to kill launcher processes", e)
                }
                
                sendResponse(output, 200, "OK")
            } else if (requestLine.startsWith("GET /hide_unlock")) {
                val hide = params["hide"]?.toBoolean() ?: true
                
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                prefs.edit().putBoolean("hide_tv_unlock_ui", hide).apply()
                
                // Broadcast to update UI
                val intent = Intent("com.lnu.volumelockr.plus.ACTION_UI_UPDATE")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                sendResponse(output, 200, "OK")
            } else if (requestLine.startsWith("GET /launch_app")) {
                val launchIntent = Intent(context, com.lnu.volumelockr.plus.ui.TvMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                // On Android 10+, background activity launches are blocked. 
                // We use a high-priority notification with a full-screen intent to bypass this on Android TV.
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, launchIntent, 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                
                val channelId = "launch_channel"
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(channelId, "App Launch", android.app.NotificationManager.IMPORTANCE_HIGH)
                    notificationManager.createNotificationChannel(channel)
                }
                
                val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("VolumeLockr")
                    .setContentText("Launching...")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .build()
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationManager.notify(999, notification)
                    }
                } else {
                    notificationManager.notify(999, notification)
                }
                
                // Try standard start just in case it's allowed
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {}

                // Clean up notification shortly after
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    notificationManager.cancel(999)
                }, 2000)

                sendResponse(output, 200, "OK")
            } else {
                sendResponse(output, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.e("TvRemoteServer", "Client error", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {}
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isEmpty()) return map
        query.split("&").forEach { param ->
            val kv = param.split("=")
            if (kv.size == 2) {
                map[kv[0]] = kv[1]
            }
        }
        return map
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, message: String) {
        val response = "HTTP/1.1 $statusCode $message\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n\r\n" +
                message
        output.write(response.toByteArray())
        output.flush()
    }
}
