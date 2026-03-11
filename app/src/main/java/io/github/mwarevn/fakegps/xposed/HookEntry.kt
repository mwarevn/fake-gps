package io.github.mwarevn.fakegps.xposed

import io.github.mwarevn.fakegps.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Hook chính ứng dụng của mình để hiển thị trạng thái Module Active
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            try {
                XposedHelpers.findAndHookMethod(
                    "io.github.mwarevn.fakegps.ui.viewmodel.MainViewModel",
                    lpparam.classLoader,
                    "isModuleActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("GPS Setter: Failed to hook MainViewModel: ${e.message}")
            }
        }

        // 2. Khởi tạo Hook vị trí
        // LocationHook sẽ tự xử lý logic phân tách giữa System Server và App Level
        try {
            LocationHook.initHooks(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to init LocationHook for ${lpparam.packageName}: ${e.message}")
        }

        // 3. Khởi tạo Hook cảm biến (sensor spoofing)
        try {
            SensorHook.initHooks(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to init SensorHook for ${lpparam.packageName}: ${e.message}")
        }

        // 4. Khởi tạo Hook mạng (WiFi, Bluetooth, Cell tower, System settings)
        try {
            NetworkHook.initHooks(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to init NetworkHook for ${lpparam.packageName}: ${e.message}")
        }

        // 5. Khởi tạo Hook Geocoder (reverse geocoding spoofing)
        try {
            GeocoderHook.initHooks(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to init GeocoderHook for ${lpparam.packageName}: ${e.message}")
        }
    }
}
