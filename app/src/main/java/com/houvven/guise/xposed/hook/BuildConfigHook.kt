package com.houvven.guise.xposed.hook

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.afterHookedMethod
import com.houvven.ktx_xposed.hook.findClassIfExists
import com.houvven.ktx_xposed.hook.lppram
import com.houvven.ktx_xposed.hook.setStaticField
import com.houvven.ktx_xposed.logger.XposedLogger

class BuildConfigHook : LoadPackageHandler {

    override fun onHook() {
        if (config.versionName.isBlank() && config.versionCode == -1) return

        // Apps reading their own BuildConfig.VERSION_NAME/VERSION_CODE bypass PackageManager,
        // so the fields must be rewritten as well (original Guise v1 behavior).
        // StaticFieldWriter underneath handles static final fields on modern ART.
        findClassIfExists("${lppram.packageName}.BuildConfig")?.let { buildConfigClass ->
            if (config.versionCode != -1) {
                buildConfigClass.setStaticField("VERSION_CODE", config.versionCode)
            }
            if (config.versionName.isNotBlank()) {
                buildConfigClass.setStaticField("VERSION_NAME", config.versionName)
            }
        } ?: XposedLogger.i("BuildConfigHook: ${lppram.packageName}.BuildConfig not found.")

        val packageManagerClass = findClassIfExists("android.app.ApplicationPackageManager")
            ?: PackageManager::class.java

        val replaceVersion: (com.houvven.ktx_xposed.hook.MethodHookParam) -> Unit = { param ->
            val requestedPackage = when (val argument = param.args.firstOrNull()) {
                is String -> argument
                is VersionedPackage -> argument.packageName
                else -> null
            }
            if (requestedPackage == lppram.packageName) {
                (param.result as? PackageInfo)?.apply {
                    if (config.versionName.isNotBlank()) versionName = config.versionName
                    if (config.versionCode != -1) longVersionCode = config.versionCode.toLong()
                }
            }
        }

        packageManagerClass.afterHookedMethod(
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType!!,
            callback = replaceVersion,
        )
        packageManagerClass.afterHookedMethod(
            "getPackageInfo",
            VersionedPackage::class.java,
            Int::class.javaPrimitiveType!!,
            callback = replaceVersion,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManagerClass.afterHookedMethod(
                "getPackageInfo",
                String::class.java,
                PackageManager.PackageInfoFlags::class.java,
                callback = replaceVersion,
            )
            packageManagerClass.afterHookedMethod(
                "getPackageInfo",
                VersionedPackage::class.java,
                PackageManager.PackageInfoFlags::class.java,
                callback = replaceVersion,
            )
        }
    }
}
