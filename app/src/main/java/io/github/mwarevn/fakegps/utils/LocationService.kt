package io.github.mwarevn.fakegps.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.TrafficLightNode
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.ui.MapActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.mwarevn.fakegps.domain.model.TrafficLightState
import io.github.mwarevn.fakegps.domain.model.TrafficLightStatus

class LocationService : Service() {

    private val binder = LocationBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var routeSimulator: RouteSimulator? = null
    
    private var onPositionUpdate: ((LatLng) -> Unit)? = null
    private var onRouteComplete: (() -> Unit)? = null

    private val _isPaused = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _isDriving = MutableStateFlow(false)
    val isDrivingFlow: StateFlow<Boolean> = _isDriving.asStateFlow()

    private val _trafficLightState = MutableStateFlow(TrafficLightState())
    val trafficLightStateFlow: StateFlow<TrafficLightState> = _trafficLightState.asStateFlow()

    private val _isAutoLightEnabled = MutableStateFlow(false)
    val isAutoLightEnabledFlow: StateFlow<Boolean> = _isAutoLightEnabled.asStateFlow()

    private val _trafficLightPoints = MutableStateFlow<List<TrafficLightNode>>(emptyList())
    val trafficLightPointsFlow: StateFlow<List<TrafficLightNode>> = _trafficLightPoints.asStateFlow()

    var currentRoutePoints: List<LatLng> = emptyList()
        private set
    var currentTargetLatLng: LatLng? = null
        private set
    var lastPosition: LatLng? = null
        private set
    val traveledPoints: MutableList<LatLng> = mutableListOf()

    private var currentProgressPercent = 0
    private var currentSpeedKmh = 0.0
    private var isFixedMode = false

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "location_service_channel_v3"
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        const val ACTION_SET_FIXED_LOCATION = "ACTION_SET_FIXED_LOCATION"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_PAUSE_RESUME = "ACTION_PAUSE_RESUME"
    }

    inner class LocationBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopForegroundService()
            ACTION_PAUSE_RESUME -> togglePauseResume()
            ACTION_SET_FIXED_LOCATION -> {
                isFixedMode = true
                _isDriving.value = false
                // Khi fixed location, chúng ta tắt foreground để không hiện thông báo
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_START_FOREGROUND -> {
                isFixedMode = false
                // Chỉ hiện thông báo khi chuẩn bị hoặc đang di chuyển
                startForegroundCompat("Ready for navigation")
            }
        }
        return START_STICKY
    }

    private fun togglePauseResume() {
        if (_isPaused.value) resumeSimulation() else pauseSimulation()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Fake GPS Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundCompat(status: String) {
        val notification = createDefaultNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createDefaultNotification(defaultStatus: String): Notification {
        val intent = Intent(this, MapActivity::class.java).apply { 
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP 
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopPI = PendingIntent.getService(this, 1, Intent(this, LocationService::class.java).apply { action = ACTION_STOP_SERVICE }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pausePI = PendingIntent.getService(this, 2, Intent(this, LocationService::class.java).apply { action = ACTION_PAUSE_RESUME }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val speedToShow = if (_isDriving.value) SpeedSyncManager.getActualSpeed() else currentSpeedKmh.toFloat()

        var statusText = defaultStatus
        if (_isDriving.value && !_isPaused.value) {
            statusText = when (_trafficLightState.value.status) {
                TrafficLightStatus.APPROACHING -> "Chuẩn bị dừng đèn đỏ..."
                TrafficLightStatus.WAITING -> "Đang chờ đèn đỏ: ${_trafficLightState.value.remainingSeconds} giây"
                TrafficLightStatus.PASSED -> "Đã qua giao lộ"
                TrafficLightStatus.MOVING -> "Đang di chuyển"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fake_location)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("Fake GPS: $statusText")
            .setContentText("$statusText | ${speedToShow.toInt()} km/h")
            .setProgress(100, currentProgressPercent, false)
            .addAction(if (_isPaused.value) R.drawable.ic_play else R.drawable.ic_pause, if (_isPaused.value) "Resume" else "Pause", pausePI)
            .addAction(R.drawable.ic_stop, "Stop", stopPI)
            .build()
    }

    private fun updateNotificationUI() {
        if (isFixedMode || !_isDriving.value) return
        val status = if (_isPaused.value) "Paused" else "Moving"
        val notification = createDefaultNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun updateCallbacks(onPosition: (LatLng) -> Unit, onComplete: () -> Unit) {
        this.onPositionUpdate = onPosition
        this.onRouteComplete = onComplete
    }

    fun startRouteSimulation(points: List<LatLng>, speedKmh: Double, onPosition: (LatLng) -> Unit, onComplete: () -> Unit) {
        this.onPositionUpdate = onPosition
        this.onRouteComplete = onComplete
        this.currentRoutePoints = points
        this.currentTargetLatLng = points.lastOrNull()
        this.currentSpeedKmh = speedKmh
        this.traveledPoints.clear()
        if (points.isNotEmpty()) this.traveledPoints.add(points.first())
        
        this.isFixedMode = false
        this._isPaused.value = false
        this._isDriving.value = true
        
        SpeedSyncManager.setSavedSpeed(speedKmh.toFloat())
        
        routeSimulator?.stop()
        routeSimulator = RouteSimulator(points, speedKmh, 300L, serviceScope)
        routeSimulator?.isAutoLightEnabled = _isAutoLightEnabled.value
        routeSimulator?.onTrafficLightUpdate = { state ->
            _trafficLightState.value = state
            serviceScope.launch(Dispatchers.Main) {
                updateNotificationUI()
            }
        }
        
        serviceScope.launch {
            routeSimulator?.let { sim ->
                sim.trafficLightPoints.collect { points ->
                    _trafficLightPoints.value = points
                }
            }
        }
        
        startForegroundCompat("Start navigation")
        
        routeSimulator?.start(
            onUpdate = { pos, progress ->
                lastPosition = pos
                currentProgressPercent = progress
                synchronized(traveledPoints) {
                    if (traveledPoints.isEmpty() || distanceBetween(traveledPoints.last(), pos) >= 5.0) {
                        traveledPoints.add(pos)
                    }
                }
                
                serviceScope.launch(Dispatchers.Main) {
                    onPositionUpdate?.invoke(pos)
                    if (System.currentTimeMillis() % 2000 < 500) { updateNotificationUI() }
                }
            },
            onComplete = {
                serviceScope.launch(Dispatchers.Main) {
                    _isDriving.value = false
                    onRouteComplete?.invoke()
                    // Khi hoàn thành dẫn đường, tự động ẩn thông báo
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        )
    }

    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0].toDouble()
    }

    fun stopRouteSimulation() {
        routeSimulator?.stop()
        routeSimulator = null
        _isDriving.value = false
        traveledPoints.clear()
        isFixedMode = true
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun pauseSimulation() {
        _isPaused.value = true
        routeSimulator?.pause()
        updateNotificationUI()
    }

    fun resumeSimulation() {
        _isPaused.value = false
        routeSimulator?.resume()
        updateNotificationUI()
    }

    fun setSpeed(speedKmh: Double) {
        this.currentSpeedKmh = speedKmh
        SpeedSyncManager.setSavedSpeed(speedKmh.toFloat())
        routeSimulator?.setSpeed(speedKmh)
        updateNotificationUI()
    }

    fun setAutoLightEnabled(enabled: Boolean) {
        _isAutoLightEnabled.value = enabled
        routeSimulator?.isAutoLightEnabled = enabled
    }

    fun skipCurrentTrafficLight(location: LatLng? = null) {
        routeSimulator?.skipCurrentTrafficLight(location)
    }

    private fun stopForegroundService() {
        routeSimulator?.stop()
        _isDriving.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        routeSimulator?.stop()
        super.onDestroy()
    }
}
