package io.github.mwarevn.movingsimulation.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.gsApp
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
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val MAPBOX_API_KEY = "mapbox_api_key"
    private const val VEHICLE_TYPE = "vehicle_type"
    private const val AUTO_CURVE_SPEED = "auto_curve_speed"
    private const val NAV_CONTROLS_EXPANDED = "nav_controls_expanded"

    private val pref: SharedPreferences by lazy {
        val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
        try {
            gsApp.getSharedPreferences(prefsFile, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            gsApp.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        }
    }

    val sharedPreferences: SharedPreferences get() = pref

    private fun fixPermissions() {
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
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM, value).apply(); fixPermissions() }

    var isRandomPosition: Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply(); fixPermissions() }

    var accuracy: String?
        get() = pref.getString(ACCURACY_SETTING, "10")
        set(value) { pref.edit().putString(ACCURACY_SETTING, value).apply(); fixPermissions() }

    var mapType: Int
        get() = pref.getInt(MAP_TYPE, 1)
        set(value) { pref.edit().putInt(MAP_TYPE, value).apply() }

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) { pref.edit().putInt(DARK_THEME, value).apply() }

    // FIXED: Default to FALSE so update is ENABLED by default
    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) { pref.edit().putBoolean(DISABLE_UPDATE, value).apply() }

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) { pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply(); fixPermissions() }

    var mapBoxApiKey: String?
        get() = pref.getString(MAPBOX_API_KEY, null)
        set(value) { pref.edit().putString(MAPBOX_API_KEY, value).apply() }

    var vehicleType: String
        get() = pref.getString(VEHICLE_TYPE, "MOTORBIKE") ?: "MOTORBIKE"
        set(value) { pref.edit().putString(VEHICLE_TYPE, value).apply() }

    var autoCurveSpeed: Boolean
        get() = pref.getBoolean(AUTO_CURVE_SPEED, true)
        set(value) { pref.edit().putBoolean(AUTO_CURVE_SPEED, value).apply() }

    var navControlsExpanded: Boolean
        get() = pref.getBoolean(NAV_CONTROLS_EXPANDED, true)
        set(value) { pref.edit().putBoolean(NAV_CONTROLS_EXPANDED, value).apply() }

    fun update(start: Boolean, la: Double, ln: Double, bearing: Float = 0F, speed: Float = 0F) {
        runInBackground {
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

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit) {
        GlobalScope.launch(Dispatchers.IO) { method.invoke() }
    }
}
