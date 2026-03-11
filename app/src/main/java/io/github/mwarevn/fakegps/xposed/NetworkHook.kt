package io.github.mwarevn.fakegps.xposed

import android.os.Build
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig

/**
 * NetworkHook - Xposed hooks for network-related anti-detection.
 *
 * Handles:
 * - WiFi scan blocking (prevents location triangulation via WiFi)
 * - Bluetooth/BLE scan blocking (prevents location via BLE beacons)
 * - Cell tower info hiding (prevents location via cell towers)
 * - System settings spoofing (wifi_scan_always_enabled, ble_scan_always_enabled)
 */
object NetworkHook {

    private val settings = Xshare()

    @JvmStatic
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) return
        if (lpparam.packageName == "android") return

        settings.reload()
        if (!settings.isStarted) return

        try {
            hookWifiScan(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter NetworkHook: WiFi hook failed: ${e.message}")
        }

        try {
            hookBluetoothScan(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter NetworkHook: Bluetooth hook failed: ${e.message}")
        }

        try {
            hookCellTower(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter NetworkHook: Cell tower hook failed: ${e.message}")
        }

        try {
            hookSystemSettings(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter NetworkHook: System settings hook failed: ${e.message}")
        }
    }

    // ========== WiFi Scan Block ==========

    private fun hookWifiScan(lpparam: XC_LoadPackage.LoadPackageParam) {
        val wifiManagerClass = XposedHelpers.findClassIfExists(
            "android.net.wifi.WifiManager", lpparam.classLoader
        ) ?: return

        // Block getScanResults() -> return empty list
        XposedHelpers.findAndHookMethod(wifiManagerClass, "getScanResults", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                settings.reload()
                if (settings.isStarted && settings.isNetworkSimEnabled) {
                    param.result = emptyList<Any>()
                }
            }
        })

        // Block startScan() -> return false
        try {
            XposedHelpers.findAndHookMethod(wifiManagerClass, "startScan", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    settings.reload()
                    if (settings.isStarted && settings.isNetworkSimEnabled) {
                        param.result = false
                    }
                }
            })
        } catch (e: Throwable) { /* startScan may not exist on all APIs */ }
    }

    // ========== Bluetooth Scan Block ==========

    private fun hookBluetoothScan(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook BluetoothAdapter
        val btAdapterClass = XposedHelpers.findClassIfExists(
            "android.bluetooth.BluetoothAdapter", lpparam.classLoader
        )

        if (btAdapterClass != null) {
            // getBondedDevices() -> empty set
            try {
                XposedHelpers.findAndHookMethod(btAdapterClass, "getBondedDevices", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        settings.reload()
                        if (settings.isStarted && settings.isBluetoothSpoofEnabled) {
                            param.result = emptySet<Any>()
                        }
                    }
                })
            } catch (e: Throwable) { }
        }

        // Hook BluetoothLeScanner.startScan() -> no-op
        val bleScannerClass = XposedHelpers.findClassIfExists(
            "android.bluetooth.le.BluetoothLeScanner", lpparam.classLoader
        )

        if (bleScannerClass != null) {
            try {
                XposedBridge.hookAllMethods(bleScannerClass, "startScan", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        settings.reload()
                        if (settings.isStarted && settings.isBtScanSpoofEnabled) {
                            param.result = null
                        }
                    }
                })
            } catch (e: Throwable) { }
        }
    }

    // ========== Cell Tower Spoofing ==========

    private fun hookCellTower(lpparam: XC_LoadPackage.LoadPackageParam) {
        val telephonyClass = XposedHelpers.findClassIfExists(
            "android.telephony.TelephonyManager", lpparam.classLoader
        ) ?: return

        // getCellLocation() -> null
        try {
            XposedHelpers.findAndHookMethod(telephonyClass, "getCellLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    settings.reload()
                    if (settings.isStarted && settings.isCellSpoofEnabled) {
                        param.result = null
                    }
                }
            })
        } catch (e: Throwable) { }

        // getAllCellInfo() -> empty list
        try {
            XposedHelpers.findAndHookMethod(telephonyClass, "getAllCellInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    settings.reload()
                    if (settings.isStarted && settings.isCellSpoofEnabled) {
                        param.result = emptyList<Any>()
                    }
                }
            })
        } catch (e: Throwable) { }

        // getNeighboringCellInfo() -> empty list (deprecated but still used by some apps)
        try {
            XposedHelpers.findAndHookMethod(telephonyClass, "getNeighboringCellInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    settings.reload()
                    if (settings.isStarted && settings.isCellSpoofEnabled) {
                        param.result = emptyList<Any>()
                    }
                }
            })
        } catch (e: Throwable) { }
    }

    // ========== System Settings Spoofing ==========

    private fun hookSystemSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Fake wifi_scan_always_enabled and ble_scan_always_enabled to always return 1 (enabled)
        try {
            XposedBridge.hookAllMethods(Settings.Global::class.java, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    settings.reload()
                    if (!settings.isStarted) return

                    val key = param.args.getOrNull(1) as? String ?: return

                    if (key == "wifi_scan_always_enabled" && settings.isWifiScanSpoofEnabled) {
                        param.result = 1
                    }
                    if (key == "ble_scan_always_enabled" && settings.isBtScanSpoofEnabled) {
                        param.result = 1
                    }
                }
            })
        } catch (e: Throwable) { }
    }
}
