package com.example.smartlocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.smartlocation.repository.LocationRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import java.util.*

data class TrackSegment(
    val startTime: Long,
    val endTime: Long? = null,
    val points: MutableList<Location> = mutableListOf(),
    var totalDistance: Float = 0f
) {
    fun durationSeconds(): Long {
        val end = endTime ?: System.currentTimeMillis()
        return (end - startTime) / 1000
    }

    fun avgSpeed(): Float {
        if (points.size < 2 || durationSeconds() == 0L) return 0f
        return totalDistance / durationSeconds()
    }
}

data class SatelliteInfo(
    val prn: Int,
    val snr: Float,
    val azimuth: Float,
    val elevation: Float,
    val usedInFix: Boolean
)

class LocationService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID = "location_channel"
        const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "SmartLocation:WakeLock"
        private const val RESTART_INTERVAL_MS = 15 * 60 * 1000L // 15 分鐘

        // 位置更新 Flow
        private val _locationUpdates = MutableSharedFlow<Location>(replay = 1)
        val locationUpdates: SharedFlow<Location> = _locationUpdates

        // 軌跡更新 Flow
        private val _trackUpdates = MutableSharedFlow<TrackSegment>(replay = 1)
        val trackUpdates: SharedFlow<TrackSegment> = _trackUpdates

        // 衛星訊號 Flow
        private val _satelliteUpdates = MutableSharedFlow<List<SatelliteInfo>>(replay = 1)
        val satelliteUpdates: SharedFlow<List<SatelliteInfo>> = _satelliteUpdates
    }

    private lateinit var locationManager: LocationManager
    private lateinit var repository: LocationRepository
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var restartHandler: Handler
    private lateinit var restartRunnable: Runnable

    // 緩衝參數
    private val locationBuffer = mutableListOf<Location>()
    private val MAX_BUFFER_SIZE = 5
    private val MAX_SPEED_THRESHOLD = 50.0f // 50 m/s = 180 km/h

    // 軌跡記錄
    private var currentTrack: TrackSegment? = null
    private val completedTracks = mutableListOf<TrackSegment>()

    // 衛星訊號
    private var satelliteCount = 0
    private var usedSatelliteCount = 0

    // 系統廣播接收器（監聽關機、重啟等事件）
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_POWER_CONNECTED -> {
                    // 系統喚醒或重啟，確保服務繼續運行
                    startLocationUpdates()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        repository = LocationRepository(this)

        // 初始化 WakeLock（防止 CPU 休眠）
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        )
        wakeLock.setReferenceCounted(false) // 確保只持有一個 WakeLock

        // 初始化守護者 Handler（定時檢查服務狀態）
        restartHandler = Handler(Looper.getMainLooper())
        restartRunnable = Runnable {
            // 每 15 分鐘檢查一次，如果服務被殺掉就重啟
            if (!isServiceRunning()) {
                startLocationUpdates()
                startNewTrack()
            }
            restartHandler.postDelayed(restartRunnable, RESTART_INTERVAL_MS)
        }

        // 註冊系統廣播接收器
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(systemReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("定位中..."))
        startLocationUpdates()
        startNewTrack()

        // 獲取 WakeLock（防止休眠）
        if (!wakeLock.isHeld) {
            wakeLock.acquire(24 * 60 * 60 * 1000L) // 最長 24 小時
        }

        // 啟動守護者（定時檢查）
        restartHandler.postDelayed(restartRunnable, RESTART_INTERVAL_MS)

        return START_STICKY // 系統殺掉服務後會自動重啟
    }

    private fun isServiceRunning(): Boolean {
        // 簡單檢查：如果 currentTrack 不為 null，則服務應該在運行
        return currentTrack != null
    }

    private fun startNewTrack() {
        currentTrack = TrackSegment(
            startTime = System.currentTimeMillis()
        )
    }

    private fun startLocationUpdates() {
        try {
            // 極致精準 GPS 定位（最高頻率）
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 秒
                0f,     // 0 公尺
                this
            )

            // 網路定位（輔助）
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                3000L, // 3 秒
                0f,
                this
            )

            // 被動定位
            if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    2000L,
                    0f,
                    this
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        // 緩衝：檢查速度是否異常
        if (locationBuffer.isNotEmpty()) {
            val lastLocation = locationBuffer.last()
            val distance = lastLocation.distanceTo(location)
            val timeDiff = (location.time - lastLocation.time) / 1000.0f
            if (timeDiff > 0) {
                val speed = distance / timeDiff
                if (speed > MAX_SPEED_THRESHOLD) {
                    return
                }
            }
        }

        // 加入緩衝區
        locationBuffer.add(location)
        if (locationBuffer.size > MAX_BUFFER_SIZE) {
            locationBuffer.removeAt(0)
        }

        // 計算移動平均
        val avgLatitude = locationBuffer.map { it.latitude }.average()
        val avgLongitude = locationBuffer.map { it.longitude }.average()
        val avgAccuracy = locationBuffer.map { it.accuracy }.average()

        val filteredLocation = Location(location)
        filteredLocation.latitude = avgLatitude
        filteredLocation.longitude = avgLongitude
        filteredLocation.accuracy = avgAccuracy.toFloat()

        // 軌跡記錄
        currentTrack?.let { track ->
            if (track.points.isNotEmpty()) {
                val lastPoint = track.points.last()
                val distance = lastPoint.distanceTo(filteredLocation)
                track.totalDistance += distance
            }
            track.points.add(filteredLocation)

            // 廣播軌跡更新
            try {
                runBlocking {
                    _trackUpdates.emit(track)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 儲存位置點
        repository.saveLocation(filteredLocation)

        // 廣播位置更新
        try {
            runBlocking {
                _locationUpdates.emit(filteredLocation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 更新通知
        val notification = createNotification(
            "緯度: ${String.format("%.6f", filteredLocation.latitude)}, 經度: ${String.format("%.6f", filteredLocation.longitude)}"
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private val binder = object : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)

        // 釋放 WakeLock
        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        // 停止守護者
        restartHandler.removeCallbacks(restartRunnable)

        // 取消註冊廣播接收器
        unregisterReceiver(systemReceiver)

        // 完成當前軌跡
        currentTrack?.let {
            it.endTime = System.currentTimeMillis()
            completedTracks.add(it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定位服務",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Location")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    fun getCurrentTrack(): TrackSegment? = currentTrack
    fun getCompletedTracks(): List<TrackSegment> = completedTracks.toList()
    fun getSatelliteCount(): Int = satelliteCount
    fun getUsedSatelliteCount(): Int = usedSatelliteCount
}
