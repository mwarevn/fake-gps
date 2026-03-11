package io.github.mwarevn.fakegps.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig

/**
 * GeocoderHook - Xposed hook for Geocoder reverse lookup spoofing.
 *
 * When enabled, intercepts Geocoder.getFromLocation(lat, lng, maxResults)
 * and replaces the real lat/lng with the fake GPS coordinates so that
 * reverse geocoding returns address data consistent with the spoofed location.
 *
 * Hooks are registered unconditionally at load time.
 * isStarted and toggle are checked at RUNTIME inside each callback.
 */
object GeocoderHook {

    private val settings = Xshare()

    @JvmStatic
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) return
        if (lpparam.packageName == "android") return

        try {
            hookGeocoder(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter GeocoderHook: Failed to init: ${e.message}")
        }
    }

    /** Check at runtime whether GPS spoofing is active */
    private fun isActive(): Boolean {
        settings.reload()
        return settings.isStarted
    }

    private fun hookGeocoder(lpparam: XC_LoadPackage.LoadPackageParam) {
        val geocoderClass = XposedHelpers.findClassIfExists(
            "android.location.Geocoder", lpparam.classLoader
        ) ?: return

        // Hook getFromLocation(double latitude, double longitude, int maxResults)
        // Replace lat/lng with fake GPS coordinates
        try {
            XposedHelpers.findAndHookMethod(
                geocoderClass,
                "getFromLocation",
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isActive() && settings.isGeocoderSpoofEnabled) {
                            param.args[0] = settings.getLat
                            param.args[1] = settings.getLng
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter GeocoderHook: getFromLocation hook failed: ${e.message}")
        }

        // API 33+ has a new async overload - hook it too
        try {
            val listenerClass = XposedHelpers.findClassIfExists(
                "android.location.Geocoder\$GeocodeListener", lpparam.classLoader
            )
            if (listenerClass != null) {
                XposedHelpers.findAndHookMethod(
                    geocoderClass,
                    "getFromLocation",
                    Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    listenerClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isActive() && settings.isGeocoderSpoofEnabled) {
                                param.args[0] = settings.getLat
                                param.args[1] = settings.getLng
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) { /* API 33+ async variant may not exist */ }
    }
}
