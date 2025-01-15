package yukams.app.background_locator_2

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import yukams.app.background_locator_2.pluggables.DisposePluggable
import yukams.app.background_locator_2.pluggables.InitPluggable
import yukams.app.background_locator_2.pluggables.Pluggable
import yukams.app.background_locator_2.provider.*
import java.util.HashMap
import androidx.core.app.ActivityCompat

class IsolateHolderService : MethodChannel.MethodCallHandler, LocationUpdateListener, Service() {

    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"
        @JvmStatic
        val ACTION_START = "START"
        @JvmStatic
        val ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION"
        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        @JvmStatic
        var backgroundEngine: FlutterEngine? = null
        @JvmStatic
        private val notificationId = 1
        @JvmStatic
        var isServiceRunning = false
        @JvmStatic
        var isServiceInitialized = false

        fun getBinaryMessenger(context: Context?): BinaryMessenger? {
            val messenger = backgroundEngine?.dartExecutor?.binaryMessenger
            return messenger ?: context?.let {
                backgroundEngine = FlutterEngine(it)
                backgroundEngine?.dartExecutor?.binaryMessenger
            }
        }
    }

    private var notificationChannelName = "Flutter Locator Plugin"
    private var notificationTitle = "Start Location Tracking"
    private var notificationMsg = "Track location in background"
    private var notificationBigMsg =
        "Background location is on to keep the app up-to-date with your location. This is required for main features to work properly when the app is not running."
    private var notificationIconColor = 0
    private var icon = 0
    private var wakeLockTime = 60 * 60 * 1000L // 1 hour default wake lock time
    private var locatorClient: BLLocationProvider? = null
    internal lateinit var backgroundChannel: MethodChannel
    internal var context: Context? = null
    private var pluggables: ArrayList<Pluggable> = ArrayList()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startLocatorService(this)
        startForeground(notificationId, getNotification())
    }

    private fun start() {
        // Acquire wake lock to prevent the service from being killed
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(wakeLockTime)
            }
        }

        // Start the service as a foreground service with a notification
        val notification = getNotification()
        startForeground(notificationId, notification)

        pluggables.forEach {
            context?.let { it1 -> it.onServiceStart(it1) }
        }
    }

    private fun getNotification(): Notification {
        // Create a notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = 'Keys.CHANNEL_ID'
            val channelName = notificationChannelName.takeIf { it.isNotEmpty() } ?: "Default Channel"
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(channelId, channelName, importance)

            // Check if the channel already exists
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(channelId) == null) {
                notificationManager.createNotificationChannel(channel)
            }
        }

        val intent = Intent(this, getMainActivityClass(this))
        intent.action = Keys.NOTIFICATION_ACTION

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, Keys.CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationMsg)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationBigMsg)
            )
            .setSmallIcon(icon)
            .setColor(notificationIconColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // so when data is updated, don't make sound and alert in Android 8.0+
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e("IsolateHolderService", "onStartCommand => intent.action11111 : ${intent?.action}")

        // Check if location permissions are granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("IsolateHolderService", "app has crashed, stopping it")
            stopSelf()
        } else {
            Log.e("IsolateHolderService", "LOG Ở ĐÂY 1111 ${isServiceRunning}")
            when {
                ACTION_SHUTDOWN == intent?.action -> {
                    isServiceRunning = false
                    shutdownHolderService()
                }
                ACTION_START == intent?.action -> {
                    if (isServiceRunning) {
                        Log.e("IsolateHolderService", "isServiceRunning = 1")
                        isServiceRunning = false
                        shutdownHolderService()
                    }
                    if (!isServiceRunning) {
                        Log.e("IsolateHolderService", "isServiceRunning = 0")
                        isServiceRunning = true
                        startHolderService(intent)
                    }
                }
                ACTION_UPDATE_NOTIFICATION == intent?.action -> {
                    if (isServiceRunning) {
                        updateNotification(intent)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun startHolderService(intent: Intent) {
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 1")
        notificationChannelName =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME).toString()
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 2")
        notificationTitle =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 3")
        notificationMsg = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 4")
        notificationBigMsg =
            intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 5")

        val iconNameDefault = "ic_launcher"
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 6")
        var iconName = intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON)
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 7")
        if (iconName == null || iconName.isEmpty()) {
            iconName = iconNameDefault
        }
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 8")
        icon = resources.getIdentifier(iconName, "mipmap", packageName)
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 9")
        notificationIconColor =
            intent.getLongExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR, 0).toInt()
        wakeLockTime = intent.getIntExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, 60) * 60 * 1000L
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 10")

        locatorClient = context?.let { getLocationClient(it) }
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 11")
        locatorClient?.requestLocationUpdates(getLocationRequest(intent))
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 12")

        // Fill pluggable list
        if (intent.hasExtra(Keys.SETTINGS_INIT_PLUGGABLE)) {
            Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 13")
            pluggables.add(InitPluggable())
            Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 14")
        }
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 15")
        if (intent.hasExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE)) {
            Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 16")
            pluggables.add(DisposePluggable())
            Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 17")
        }
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 18")
        start()
        Log.e("IsolateHolderService", "startHolderService =====> Start ở đấy 19")
    }

    private fun shutdownHolderService() {
        Log.e("IsolateHolderService", "shutdownHolderService")
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) {
                    release()
                }
            }
        }

        locatorClient?.removeLocationUpdates()
        stopForeground(true)
        stopSelf()

        pluggables.forEach {
            context?.let { it1 -> it.onServiceDispose(it1) }
        }
    }

    private fun updateNotification(intent: Intent) {
        Log.e("IsolateHolderService", "updateNotification")
        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
            notificationTitle =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
            notificationMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG).toString()
        }

        if (intent.hasExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
            notificationBigMsg =
                intent.getStringExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG).toString()
        }

        val notification = getNotification()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                Keys.METHOD_SERVICE_INITIALIZED -> {
                    isServiceRunning = true
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            Log.e("IsolateHolderService", e.toString())
        }
    }
}
