package com.houvven.guise.xposed

import com.houvven.guise.xposed.config.HooksValue
import com.houvven.guise.xposed.config.ModuleConfig

/**
 * The smallest set of hook groups required by one target configuration.
 *
 * Keeping this decision separate from hook construction prevents inactive hook implementations
 * from being initialized in the target process and makes the runtime footprint auditable.
 */
internal enum class HookFeature {
    BATTERY,
    LOCALE,
    TIME_ZONE,
    LOCATION,
    CELL_LOCATION,
    NETWORK,
    OS_BUILD,
    DISPLAY_DENSITY,
    SCREENSHOTS,
    UNIQUE_ID,
    BLANK_PASS,
    APPLICATION_LIST,
    APP_VERSION,
    PRIVACY_GUARD,
}

internal fun ModuleConfig.activeHookFeatures(): List<HookFeature> = buildList {
    if (batteryLevel != -1) add(HookFeature.BATTERY)
    if (language.isNotBlank()) add(HookFeature.LOCALE)
    if (timeZone.isNotBlank()) add(HookFeature.TIME_ZONE)
    if (
        longitude != -1.0 ||
        latitude != -1.0 ||
        makeWifiLocationFail ||
        makeCellLocationFail
    ) {
        add(HookFeature.LOCATION)
    }
    if ((lac != -1 || cid != -1) && !makeCellLocationFail) add(HookFeature.CELL_LOCATION)
    if (
        networkType != HooksValue.NET_UNHOOK ||
        wifiSSID.isNotBlank() ||
        wifiBSSID.isNotBlank() ||
        wifiMacAddress.isNotBlank() ||
        simOperator.isNotBlank() ||
        simOperatorName.isNotBlank() ||
        simCountry.isNotBlank()
    ) {
        add(HookFeature.NETWORK)
    }
    if (
        brand.isNotBlank() ||
        manufacturer.isNotBlank() ||
        model.isNotBlank() ||
        product.isNotBlank() ||
        device.isNotBlank() ||
        board.isNotBlank() ||
        hardware.isNotBlank() ||
        buildId.isNotBlank() ||
        fingerPrint.isNotBlank() ||
        androidVersion.isNotBlank() ||
        sdkInt != -1
    ) {
        add(HookFeature.OS_BUILD)
    }
    if (densityDpi != -1) add(HookFeature.DISPLAY_DENSITY)
    if (screenshotsFlag != HooksValue.SCREENSHOTS_UNHOOK) add(HookFeature.SCREENSHOTS)
    if (androidId.isNotBlank() || imei.isNotBlank() || phoneNum.isNotBlank()) {
        add(HookFeature.UNIQUE_ID)
    }
    if (passAudio || passVideo || passPhoto || passContacts) add(HookFeature.BLANK_PASS)
    if (passApplications) add(HookFeature.APPLICATION_LIST)
    if (versionName.isNotBlank() || versionCode != -1) add(HookFeature.APP_VERSION)
    if (privacyBlockMedia || privacyBlockCallLogs || privacyBlockSms || privacyBlockMms || privacyBlockFiles || privacyBlockedPaths.any { it.isNotBlank() }) {
        add(HookFeature.PRIVACY_GUARD)
    }
}
