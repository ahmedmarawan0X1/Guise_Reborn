package com.houvven.guise.xposed.hook.location

import android.net.wifi.WifiInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.INCLUDE_LOCATION_DATA_NONE
import android.telephony.cdma.CdmaCellLocation
import android.telephony.gsm.GsmCellLocation
import com.houvven.ktx_xposed.hook.afterHookedMethod
import com.houvven.ktx_xposed.hook.beforeHookedMethod
import com.houvven.ktx_xposed.hook.findMethodExactIfExists
import com.houvven.ktx_xposed.hook.setMethodResult
import java.util.concurrent.Executor

@Suppress("DEPRECATION")
open class LocationHookBase {

    protected fun makeCellLocationFail() {
        TelephonyManager::class.java.run {
            setMethodResult("getCellLocation", null)
            setMethodResult("getAllCellInfo", emptyList<CellInfo>())
            setMethodResult("getNeighboringCellInfo", emptyList<Any>())
            setOptionalNoArgMethodResult("getLastKnownCellIdentity", null)
            hideCellInfoUpdates()
            removeLegacyCellLocationCallbacks()
            requestLocationFreeTelephonyCallbacks()
            requestLocationFreeServiceState()
        }

        GsmCellLocation::class.java.run {
            setMethodResult("getPsc", -1)
            setMethodResult("getLac", -1)
            setMethodResult("getCid", -1)
        }
        CdmaCellLocation::class.java.run {
            setMethodResult("getBaseStationId", -1)
            setMethodResult("getBaseStationLatitude", -1)
            setMethodResult("getBaseStationLongitude", -1)
            setMethodResult("getNetworkId", -1)
            setMethodResult("getSystemId", -1)
        }
        hideModernCellIdentities()
    }

    protected fun makeWifiLocationFail() {
        WifiManager::class.java.run {
            desensitizeScanResults()
            setMethodResult("isScanAlwaysAvailable", false)
            setMethodResult("isWifiEnabled", false)
            setMethodResult("getWifiState", WifiManager.WIFI_STATE_DISABLED)
        }
        WifiInfo::class.java.run {
            setMethodResult("getSSID", WifiManager.UNKNOWN_SSID)
            setMethodResult("getBSSID", MASKED_MAC_ADDRESS)
            setMethodResult("getMacAddress", MASKED_MAC_ADDRESS)
            setOptionalNoArgMethodResult("getApMldMacAddress", null)
            setOptionalNoArgMethodResult("getAffiliatedMloLinks", emptyList<Any>())
            setOptionalNoArgMethodResult("getAssociatedMloLinks", emptyList<Any>())
            setOptionalNoArgMethodResult("getInformationElements", emptyList<Any>())
        }
    }

    private fun Class<*>.hideCellInfoUpdates() {
        beforeHookedMethod(
            "requestCellInfoUpdate",
            Executor::class.java,
            TelephonyManager.CellInfoCallback::class.java,
        ) { param ->
            val executor = param.args[0] as Executor
            val callback = param.args[1] as TelephonyManager.CellInfoCallback
            param.result = null
            Handler(Looper.getMainLooper()).post {
                executor.execute { callback.onCellInfo(emptyList()) }
            }
        }
    }

    private fun Class<*>.removeLegacyCellLocationCallbacks() {
        beforeHookedMethod(
            "listen",
            PhoneStateListener::class.java,
            Int::class.javaPrimitiveType!!,
        ) { param ->
            val events = param.args[1] as Int
            val filteredEvents = withoutCellLocationEvents(events)
            if (filteredEvents == PhoneStateListener.LISTEN_NONE) {
                param.result = null
            } else {
                param.args[1] = filteredEvents
            }
        }
    }

    private fun Class<*>.requestLocationFreeTelephonyCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        beforeHookedMethod(
            "registerTelephonyCallback",
            Int::class.javaPrimitiveType!!,
            Executor::class.java,
            TelephonyCallback::class.java,
        ) { param ->
            param.args[0] = INCLUDE_LOCATION_DATA_NONE
        }
    }

    private fun Class<*>.requestLocationFreeServiceState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // TelephonyManager#getServiceState(int) only exists on API 35+; looking it up
        // unconditionally throws NoSuchMethodException on older releases.
        findMethodExactIfExists("getServiceState", Int::class.javaPrimitiveType!!) ?: return
        beforeHookedMethod(
            "getServiceState",
            Int::class.javaPrimitiveType!!,
        ) { param ->
            param.args[0] = INCLUDE_LOCATION_DATA_NONE
        }
    }

    /**
     * Hides the nearby-AP list, which is what "hide Wi-Fi location info" promises.
     *
     * An empty list does deny Wi-Fi triangulation outright — that is the point of the
     * switch. It is safe to be this strict only because [LocationHook] no longer waits
     * for the system to produce a fix: it pushes the spoofed coordinate into the app's
     * listeners itself, so removing every real fix source costs the app nothing.
     */
    private fun Class<*>.desensitizeScanResults() {
        afterHookedMethod("getScanResults") { param ->
            param.result = emptyList<ScanResult>()
        }
    }

    private fun hideModernCellIdentities() {
        CellIdentityGsm::class.java.hideIntCellFields("getLac", "getCid", "getArfcn", "getBsic")
        CellIdentityWcdma::class.java.hideIntCellFields("getLac", "getCid", "getPsc", "getUarfcn")
        CellIdentityLte::class.java.hideIntCellFields(
            "getCi",
            "getPci",
            "getTac",
            "getEarfcn",
            "getBandwidth",
        )
        CellIdentityLte::class.java.setOptionalNoArgMethodResult("getBands", intArrayOf())
        CellIdentityNr::class.java.run {
            hideIntCellFields("getPci", "getTac", "getNrarfcn")
            setOptionalNoArgMethodResult("getNci", Long.MAX_VALUE)
            setOptionalNoArgMethodResult("getBands", intArrayOf())
        }
        CellIdentityTdscdma::class.java.hideIntCellFields("getLac", "getCid", "getCpid", "getUarfcn")
        CellIdentityCdma::class.java.hideIntCellFields(
            "getBasestationId",
            "getLatitude",
            "getLongitude",
            "getNetworkId",
            "getSystemId",
        )
    }

    private fun Class<*>.hideIntCellFields(vararg methodNames: String) {
        methodNames.forEach { setOptionalNoArgMethodResult(it, CellInfo.UNAVAILABLE) }
    }

    private fun Class<*>.setOptionalNoArgMethodResult(methodName: String, value: Any?) {
        findMethodExactIfExists(methodName)?.setMethodResult(value)
    }

    private companion object {
        const val MASKED_MAC_ADDRESS = "02:00:00:00:00:00"
    }
}

@Suppress("DEPRECATION")
internal fun withoutCellLocationEvents(events: Int): Int =
    events and (
        PhoneStateListener.LISTEN_CELL_LOCATION or PhoneStateListener.LISTEN_CELL_INFO
    ).inv()
