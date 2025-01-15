package yukams.app.background_locator_2

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import yukams.app.background_locator_2.provider.BLLocationProvider
import yukams.app.background_locator_2.provider.GoogleLocationProviderClient
import yukams.app.background_locator_2.provider.LocationClient
import yukams.app.background_locator_2.provider.PreferencesManager
import java.util.HashMap

class IsolateHolderService : Service(), MethodChannel.MethodCallHandler, BLLocationProvider.LocationUpdateListener {

    companion object {
        const val ACTION_SHUTDOWN = "SHUTDOWN"
        const val ACTION_START = "START"
        const val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"
        private const val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        private const val NOTIFICATION_CHANNEL_ID = "FlutterLocatorNotificationChannel"
        private const val NOTIFICATION_ID = 1

        var isServiceRunning = false
        var flutterEngine: FlutterEngine? = null

        fun ensureFlutterEngine(context: Context): FlutterEngine {
            return flutterEngine ?: FlutterEngine(context).also { flutterEngine = it }
        }
    }

    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Tracking location in the background"
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private lateinit var methodChannel: MethodChannel
    private var locationProvider: BLLocationProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initializeFlutterChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocationTracking(intent)
            ACTION_UPDATE_NOTIFICATION -> updateNotification(intent)
            ACTION_SHUTDOWN -> stopService()
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun startLocationTracking(intent: Intent) {
        if (isServiceRunning) return
        isServiceRunning = true

        acquireWakeLock()
        setupLocationProvider(intent)
    }

    private fun stopService() {
        isServiceRunning = false
        releaseWakeLock()
        locationProvider?.removeLocationUpdates()
        stopForeground(true)
        stopSelf()
    }

    private fun setupLocationProvider(intent: Intent) {
        locationProvider = when (PreferencesManager.getLocationClient(this)) {
            LocationClient.Google -> GoogleLocationProviderClient(this, this)
            else -> null // Add other providers if needed
        }
        locationProvider?.requestLocationUpdates()
    }

    private fun initializeFlutterChannel() {
        val engine = ensureFlutterEngine(this)
        methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "background_locator/method_channel")
        methodChannel.setMethodCallHandler(this)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(wakeLockTime)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationMsg)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(intent: Intent) {
        notificationTitle = intent.getStringExtra("notification_title") ?: notificationTitle
        notificationMsg = intent.getStringExtra("notification_msg") ?: notificationMsg
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onLocationUpdated(locationData: HashMap<Any, Any>) {
        methodChannel.invokeMethod("locationUpdated", locationData)
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "stopService" -> stopService()
            else -> result.notImplemented()
        }
    }
}
