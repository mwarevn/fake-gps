package io.github.mwarevn.fakegps.xposed

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.utils.SpeedSyncManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.*
import java.lang.reflect.Method
import java.lang.reflect.Field

object LocationHook {

    private var newlat: Double = 21.0285
    private var newlng: Double = 105.8542
    private var bearing: Float = 0F
    private var speed: Float = 0F
    private var accuracy: Float = 10f
    private var isStartedCache: Boolean = false
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    @JvmStatic
    private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID, "com.android.location.fused")

    private fun updateLocation(force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            if (force || now - mLastUpdated > 1000) {
                settings.reload()
                mLastUpdated = now
                isStartedCache = settings.isStarted
                if (isStartedCache) {
                    newlat = settings.getLat
                    newlng = settings.getLng
                    accuracy = settings.accuracy?.toFloatOrNull() ?: 10f
                    val syncedBearing = SpeedSyncManager.getBearing()
                    bearing = if (syncedBearing != 0f) syncedBearing else settings.getBearing
                    val syncedSpeed = SpeedSyncManager.getActualSpeed()
                    speed = if (syncedSpeed > 0f) SpeedSyncManager.speedKmhToMs(syncedSpeed) else settings.getSpeed
                }
            }
        } catch (e: Exception) { }
    }
    
    private fun setLocationFields(location: Location) {
        try {
            location.latitude = newlat
            location.longitude = newlng
            location.bearing = bearing
            location.speed = speed
            location.accuracy = accuracy
            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.verticalAccuracyMeters = 0.5f
                location.bearingAccuracyDegrees = 5f
                location.speedAccuracyMetersPerSecond = 0.1f
            }

            try {
                HiddenApiBypass.invoke(Location::class.java, location, "setFieldsMask", 255)
            } catch (e: Throwable) {}
            
            try {
                HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
            } catch (e: Exception) { }
            
            val extras = location.extras ?: Bundle()
            extras.remove("mockLocation")
            extras.putBoolean("mockLocation", false)
            location.extras = extras
        } catch (e: Throwable) { }
    }

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        updateLocation(true)
        hookSystemSettings(lpparam)

        if (lpparam.packageName == "android") {
            hookSystemServer(lpparam)
            return
        }

        if (ignorePkg.contains(lpparam.packageName)) return

        // Khi global hook (system_hooked) đang bật, system server đã xử lý GPS cho toàn bộ app.
        // Chỉ cần hook app-level GPS khi KHÔNG dùng global hook.
        if (settings.isHookedSystem) return

        if (lpparam.packageName == "com.google.android.gms") hookGmsCore(lpparam)
        hookApplicationLevel(lpparam)
    }

    private fun hookSystemSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(Settings.Secure::class.java, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive() && settings.isAccuracySpoofEnabled) {
                        if (param.args[1] == "location_mode") param.result = 3
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val lmsClass = XposedHelpers.findClassIfExists("com.android.server.LocationManagerService", lpparam.classLoader)
            if (lmsClass != null) {
                XposedBridge.hookAllMethods(lmsClass, "getLastLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val loc = Location(LocationManager.GPS_PROVIDER)
                            setLocationFields(loc)
                            param.result = loc
                        }
                    }
                })
            }

            val lpmClass = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", lpparam.classLoader)
            if (lpmClass != null) {
                XposedBridge.hookAllMethods(lpmClass, "onReportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val arg = param.args[0] ?: return
                            if (arg is Location) {
                                setLocationFields(arg)
                            } else {
                                processLocationSafely(arg)
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) { }
    }

    private fun hookGmsCore(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val callbackClass = XposedHelpers.findClassIfExists("com.google.android.gms.location.internal.IFusedLocationProviderCallback.Stub", lpparam.classLoader)
            if (callbackClass != null) {
                XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            param.args[0]?.let { processLocationSafely(it) }
                        }
                    }
                })
            }
        } catch (e: Throwable) { }
    }

    private fun processLocationSafely(obj: Any) {
        try {
            val clazz = obj.javaClass
            val getLocations = findMethodRecursively(clazz, "getLocations")
            val locations = if (getLocations != null) {
                getLocations.isAccessible = true
                getLocations.invoke(obj) as? List<*>
            } else {
                val field = findFieldRecursively(clazz, "mLocations") ?: findFieldRecursively(clazz, "locations")
                if (field != null) {
                    field.isAccessible = true
                    field.get(obj) as? List<*>
                } else null
            }
            locations?.forEach { (it as? Location)?.let { loc -> setLocationFields(loc) } }
        } catch (e: Throwable) { }
    }

    private fun findMethodRecursively(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try { return current.getDeclaredMethod(name) } catch (e: NoSuchMethodException) { }
            current = current.superclass
        }
        return null
    }

    private fun findFieldRecursively(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try { return current.getDeclaredField(name) } catch (e: NoSuchFieldException) { }
            current = current.superclass
        }
        return null
    }

    private fun hookApplicationLevel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locClass = Location::class.java
            XposedHelpers.findAndHookMethod(locClass, "getLatitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlat }
            })
            XposedHelpers.findAndHookMethod(locClass, "getLongitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlng }
            })
            XposedHelpers.findAndHookMethod(locClass, "isFromMockProvider", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = false }
            })
        } catch (e: Throwable) { }
    }

    private fun isHookActive(): Boolean {
        updateLocation()
        return isStartedCache
    }
}
