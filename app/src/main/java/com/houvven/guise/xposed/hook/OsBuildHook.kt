package com.houvven.guise.xposed.hook

import android.os.Build
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.beforeHookAllMethods
import com.houvven.ktx_xposed.hook.findClass
import com.houvven.ktx_xposed.hook.setStaticField
import com.houvven.ktx_xposed.logger.XposedLogger
import com.houvven.ktx_xposed.utils.runXposedCatching

class OsBuildHook : LoadPackageHandler {

    override fun onHook() {
        val fakedProps = mutableMapOf<String, String>()

        config.run {
            // Triple: Build field name, configured value, matching ro.* property
            listOf(
                Triple("BRAND", brand, "ro.product.brand"),
                Triple("MANUFACTURER", manufacturer, "ro.product.manufacturer"),
                Triple("MODEL", model, "ro.product.model"),
                Triple("PRODUCT", product, "ro.product.name"),
                Triple("DEVICE", device, "ro.product.device"),
                Triple("BOARD", board, "ro.product.board"),
                Triple("HARDWARE", hardware, "ro.hardware"),
                Triple("ID", buildId, "ro.build.id"),
                Triple("FINGERPRINT", fingerPrint, "ro.build.fingerprint"),
            ).forEach { (field, value, propKey) ->
                if (value.isNotBlank()) {
                    Build::class.java.setStaticField(field, value)
                    fakedProps[propKey] = value
                }
            }

            if (androidVersion.isNotBlank()) {
                Build.VERSION::class.java.setStaticField("RELEASE", androidVersion)
                fakedProps["ro.build.version.release"] = androidVersion
            }
            if (sdkInt != -1) {
                if (sdkInt < Build.VERSION.SDK_INT) {
                    // Spoofing SDK_INT below the real level makes apps take legacy
                    // branches (e.g. skipping RECEIVER_EXPORTED flags) while the
                    // framework still enforces current rules -> startup crashes.
                    XposedLogger.i(
                        "OSBuild: skip SDK_INT spoof $sdkInt < real ${Build.VERSION.SDK_INT}"
                    )
                } else {
                    Build.VERSION::class.java.setStaticField<Any>("SDK_INT", sdkInt)
                    fakedProps["ro.build.version.sdk"] = sdkInt.toString()
                }
            }
        }

        hookSystemProperties(fakedProps)
    }

    /**
     * Many apps (and native code via __system_property_get) read ro.* properties
     * directly instead of the Build fields, and on newer ART the static final
     * Build fields may not be writable at all. Intercepting SystemProperties.get
     * covers both cases — same approach as the original Guise PropertiesHooker.
     */
    private fun hookSystemProperties(fakedProps: Map<String, String>) {
        if (fakedProps.isEmpty()) return
        runXposedCatching {
            val systemProperties = findClass("android.os.SystemProperties")

            // Covers get(String) and get(String, String)
            systemProperties.beforeHookAllMethods("get") { param ->
                val key = param.args.firstOrNull() as? String ?: return@beforeHookAllMethods
                fakedProps[key]?.let { param.result = it }
            }

            fakedProps["ro.build.version.sdk"]?.toIntOrNull()?.let { sdk ->
                systemProperties.beforeHookAllMethods("getInt") { param ->
                    if (param.args.firstOrNull() == "ro.build.version.sdk") {
                        param.result = sdk
                    }
                }
            }
        }
    }
}
