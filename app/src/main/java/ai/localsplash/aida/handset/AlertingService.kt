package ai.localsplash.aida.handset

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.SubscriptionEventListener
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: SecureSessionStore
    private var pusher: Pusher? = null
    private var pollingJob: Job? = null
    private var subscribedChannels = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SecureSessionStore(this)
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        scope.cancel()
        disconnectPusher()
        super.onDestroy()
    }

    fun triggerRefresh() {
        scope.launch { refreshCalls() }
    }

    private fun startMonitoring() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            var meRefreshCounter = 0
            while (isActive) {
                val session = store.read()
                if (session != null) {
                    try {
                        if (pusher == null || meRefreshCounter % 20 == 0) { // Every 10 min (20 * 30s) or on start
                            setupPusher(session)
                        }
                        refreshCalls()
                    } catch (e: ApiException) {
                        if (e.status == 401 || e.status == 403) {
                            reAttachSilently(session)
                        }
                    } catch (_: Exception) {}
                }
                meRefreshCounter++
                delay(30_000L) // 30 s backup poll
            }
        }
    }

    private suspend fun setupPusher(session: DeviceSession) {
        val api = PlatformApi(session.serverUrl, session.token)
        val me = try {
            api.me()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                reAttachSilently(session)
                return
            }
            throw e
        }

        val pusherConfig = me.pusher ?: return
        if (pusherConfig.key.isBlank() || pusherConfig.cluster.isBlank()) return

        withContext(Dispatchers.IO) {
            disconnectPusher()
            val options = PusherOptions().apply {
                setCluster(pusherConfig.cluster)
            }
            val client = Pusher(pusherConfig.key, options)
            pusher = client

            client.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    if (change.currentState == ConnectionState.CONNECTED) {
                        scope.launch { refreshCalls() }
                    }
                }

                override fun onError(message: String?, code: String?, e: Exception?) {}
            }, ConnectionState.ALL)

            val eventListener = SubscriptionEventListener { event ->
                val parsed = runCatching {
                    PlatformApi.json.decodeFromString<PusherCallEvent>(event.data)
                }.getOrNull()
                scope.launch {
                    refreshCalls()
                    if (parsed != null && parsed.state == "screening") {
                        handleIncomingAlert(parsed.callSessionId)
                    }
                }
            }

            subscribedChannels.clear()
            for (q in me.queues) {
                if (q.channel.isNotBlank() && q.channel !in subscribedChannels) {
                    val ch = client.subscribe(q.channel)
                    ch.bind("call", eventListener)
                    subscribedChannels.add(q.channel)
                }
            }
        }
    }

    private fun disconnectPusher() {
        try {
            pusher?.disconnect()
        } catch (_: Exception) {}
        pusher = null
        subscribedChannels.clear()
    }

    suspend fun refreshCalls(): List<Call> {
        val session = store.read() ?: return emptyList()
        val api = PlatformApi(session.serverUrl, session.token)
        return try {
            val list = api.calls()
            activeCalls.value = list
            list
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                reAttachSilently(session)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun reAttachSilently(session: DeviceSession) {
        val localIps = DeviceIdentifier.getLocalIps()
        if (localIps.isEmpty()) return
        val api = PlatformApi(session.serverUrl)
        try {
            val attachResp = api.attach(
                AttachRequest(
                    appInstanceId = store.appInstanceId,
                    localIps = localIps,
                    deviceModel = DeviceIdentifier.getDeviceModel(),
                    claimedMac = DeviceIdentifier.getClaimedMac(),
                )
            )
            store.save(
                DeviceSession(
                    serverUrl = session.serverUrl,
                    token = attachResp.token,
                    expiresAt = attachResp.expiresAt,
                    device = attachResp.device,
                    pendingTakeover = session.pendingTakeover,
                )
            )
            // Re-setup pusher with new token
            store.read()?.let { setupPusher(it) }
        } catch (_: Exception) {}
    }

    private fun handleIncomingAlert(callSessionId: String) {
        val call = activeCalls.value.firstOrNull { it.id == callSessionId }
        incomingAlertCall.tryEmit(callSessionId)
        showFullScreenAlertNotification(callSessionId, call?.callerNumber ?: "Incoming Call")
    }

    private fun showFullScreenAlertNotification(callId: String, caller: String) {
        val notifyIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_CALL_ID", callId)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_handset)
            .setContentTitle("Incoming Aida Call: $caller")
            .setContentText("Aida is screening an incoming call. Tap to take over.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID_CALL_ALERT, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val fgChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Aida Handset Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Aida Handset connected for live alerts"
            }

            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Aida Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts for live incoming calls"
                enableVibration(true)
            }

            manager.createNotificationChannel(fgChannel)
            manager.createNotificationChannel(callChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_handset)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Connected to OfficePulse. Monitoring for incoming calls.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_SERVICE = "aida_alerting_service"
        const val CHANNEL_CALLS = "aida_incoming_calls"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val NOTIFICATION_ID_CALL_ALERT = 1002

        val activeCalls = MutableStateFlow<List<Call>>(emptyList())
        val incomingAlertCall = MutableSharedFlow<String>(extraBufferCapacity = 1)

        @Volatile
        var instance: AlertingService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, AlertingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
