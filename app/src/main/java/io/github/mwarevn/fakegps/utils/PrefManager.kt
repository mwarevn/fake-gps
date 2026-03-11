package io.github.mwarevn.fakegps.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

@SuppressLint("WorldReadableFiles")
object PrefManager {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val BEARING = "bearing"
    private const val SPEED = "speed"
    private const val CAMERA_BEARING = "camera_bearing"
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val VEHICLE_TYPE = "vehicle_type"
    private const val AUTO_CURVE_SPEED = "auto_curve_speed"
    private const val NAV_CONTROLS_EXPANDED = "nav_controls_expanded"
    private const val SHOW_FAKE_ICON = "show_fake_icon"
    
    // Anti-Detection Keys
    private const val SENSOR_SPOOF = "sensor_spoof"
    private const val NETWORK_SIMULATION = "network_sim"
    private const val ACCURACY_SPOOF = "accuracy_spoof"
    private const val BLUETOOTH_SPOOF = "bluetooth_spoof"
    private const val CELL_SPOOF = "cell_spoof"
    private const val GEOCODER_SPOOF = "geocoder_spoof"
    private const val WIFI_SCAN_SPOOF = "wifi_scan_spoof"
    private const val BT_SCAN_SPOOF = "bt_scan_spoof"
    private const val LOCATION_MONITOR = "location_monitor"
    private const val BLOCKED_LOCATION_APPS = "blocked_location_apps"

    private val pref: SharedPreferences by lazy {
        val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
        try {
            // Attempt to use World Readable for Xposed compatibility
            gsApp.getSharedPreferences(prefsFile, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            gsApp.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        }
    }

    val sharedPreferences: SharedPreferences get() = pref

    fun fixPermissions() {
        try {
            val dataDir = gsApp.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "${BuildConfig.APPLICATION_ID}_prefs.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                prefsDir.setReadable(true, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isStarted: Boolean get() = pref.getBoolean(START, false)
    val getLat: Double get() = pref.getFloat(LATITUDE, 21.0285F).toDouble()
    val getLng: Double get() = pref.getFloat(LONGITUDE, 105.8542F).toDouble()
    val getBearing: Float get() = pref.getFloat(BEARING, 0F)
    val getSpeed: Float get() = pref.getFloat(SPEED, 0F)

    var cameraBearing: Float
        get() = pref.getFloat(CAMERA_BEARING, 0F)
        set(value) { pref.edit().putFloat(CAMERA_BEARING, value).apply() }

    var isSystemHooked: Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, false)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM, value).commit(); fixPermissions() }

    var isRandomPosition: Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).commit(); fixPermissions() }

    var isUpdateDisabled: Boolean
        get() {
            if (!BuildConfig.DEBUG) return false
            val isCheckUpdateEnabled = pref.getBoolean("check_update_enabled", false)
            return !isCheckUpdateEnabled
        }
        set(value) { pref.edit().putBoolean("check_update_enabled", !value).apply(); fixPermissions() }

    var accuracy: String?
        get() = pref.getString(ACCURACY_SETTING, "10")
        set(value) { pref.edit().putString(ACCURACY_SETTING, value).commit(); fixPermissions() }

    // Anti-Detection Options
    var isSensorSpoofEnabled: Boolean
        get() = pref.getBoolean(SENSOR_SPOOF, true)
        set(value) { pref.edit().putBoolean(SENSOR_SPOOF, value).commit(); fixPermissions() }

    var isNetworkSimEnabled: Boolean
        get() = pref.getBoolean(NETWORK_SIMULATION, true)
        set(value) { pref.edit().putBoolean(NETWORK_SIMULATION, value).commit(); fixPermissions() }

    var isAccuracySpoofEnabled: Boolean
        get() = pref.getBoolean(ACCURACY_SPOOF, true)
        set(value) { pref.edit().putBoolean(ACCURACY_SPOOF, value).commit(); fixPermissions() }

    var isBluetoothSpoofEnabled: Boolean
        get() = pref.getBoolean(BLUETOOTH_SPOOF, true)
        set(value) { pref.edit().putBoolean(BLUETOOTH_SPOOF, value).commit(); fixPermissions() }

    var isCellSpoofEnabled: Boolean
        get() = pref.getBoolean(CELL_SPOOF, true)
        set(value) { pref.edit().putBoolean(CELL_SPOOF, value).commit(); fixPermissions() }

    var isGeocoderSpoofEnabled: Boolean
        get() = pref.getBoolean(GEOCODER_SPOOF, true)
        set(value) { pref.edit().putBoolean(GEOCODER_SPOOF, value).commit(); fixPermissions() }

    var isWifiScanSpoofEnabled: Boolean
        get() = pref.getBoolean(WIFI_SCAN_SPOOF, true)
        set(value) { pref.edit().putBoolean(WIFI_SCAN_SPOOF, value).commit(); fixPermissions() }

    var isBtScanSpoofEnabled: Boolean
        get() = pref.getBoolean(BT_SCAN_SPOOF, true)
        set(value) { pref.edit().putBoolean(BT_SCAN_SPOOF, value).commit(); fixPermissions() }

    var isLocationMonitorEnabled: Boolean
        get() = pref.getBoolean(LOCATION_MONITOR, false)
        set(value) { pref.edit().putBoolean(LOCATION_MONITOR, value).commit() }

    var blockedLocationApps: Set<String>
        get() = pref.getStringSet(BLOCKED_LOCATION_APPS, emptySet()) ?: emptySet()
        set(value) { pref.edit().putStringSet(BLOCKED_LOCATION_APPS, value).commit() }

    fun addBlockedApp(packageName: String) {
        val current = blockedLocationApps.toMutableSet()
        current.add(packageName)
        blockedLocationApps = current
    }

    fun removeBlockedApp(packageName: String) {
        val current = blockedLocationApps.toMutableSet()
        current.remove(packageName)
        blockedLocationApps = current
    }

    var mapType: Int
        get() = pref.getInt(MAP_TYPE, 1)
        set(value) { pref.edit().putInt(MAP_TYPE, value).commit() }

    fun getMapStyleUri(): String {
        return when (mapType) {
            1 -> "mapbox://styles/mapbox/streets-v12"
            2 -> "mapbox://styles/mapbox/outdoors-v12"
            3 -> "mapbox://styles/mapbox/light-v11"
            4 -> "mapbox://styles/mapbox/dark-v11"
            5 -> "mapbox://styles/mapbox/satellite-v9"
            6 -> "mapbox://styles/mapbox/satellite-streets-v12"
            7 -> "mapbox://styles/mapbox/navigation-day-v1"
            8 -> "mapbox://styles/mapbox/navigation-night-v1"
            else -> "mapbox://styles/mapbox/streets-v12"
        }
    }

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) { pref.edit().putInt(DARK_THEME, value).commit() }

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) { pref.edit().putBoolean(ENABLE_JOYSTICK, value).commit(); fixPermissions() }

    var vehicleType: String
        get() = pref.getString(VEHICLE_TYPE, "MOTORBIKE") ?: "MOTORBIKE"
        set(value) { pref.edit().putString(VEHICLE_TYPE, value).commit() }

    var autoCurveSpeed: Boolean
        get() = pref.getBoolean(AUTO_CURVE_SPEED, true)
        set(value) { pref.edit().putBoolean(AUTO_CURVE_SPEED, value).commit() }

    var navControlsExpanded: Boolean
        get() = pref.getBoolean(NAV_CONTROLS_EXPANDED, true)
        set(value) { pref.edit().putBoolean(NAV_CONTROLS_EXPANDED, value).commit() }

    var isShowFakeIcon: Boolean
        get() = pref.getBoolean(SHOW_FAKE_ICON, true)
        set(value) { pref.edit().putBoolean(SHOW_FAKE_ICON, value).commit() }

    fun update(start: Boolean, la: Double, ln: Double, bearing: Float = 0F, speed: Float = 0F) {
        pref.edit().apply {
            putFloat(LATITUDE, la.toFloat())
            putFloat(LONGITUDE, ln.toFloat())
            putFloat(BEARING, bearing)
            putFloat(SPEED, speed)
            putBoolean(START, start)
        }.commit()
        fixPermissions()
    }
}
