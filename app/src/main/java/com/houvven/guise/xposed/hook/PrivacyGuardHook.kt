package com.houvven.guise.xposed.hook

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.ModernXposedRuntime
import com.houvven.ktx_xposed.hook.beforeHookConstructor
import com.houvven.ktx_xposed.hook.beforeHookAllMethods
import java.io.File
import java.io.FileNotFoundException

/**
 * PrivacyGuard-derived privacy filtering layer integrated into Guise.
 *
 * Existing Guise media/contact blank-pass controls remain compatible. This hook
 * adds the stronger URI/filesystem coverage from PrivacyGuard without creating
 * a second Xposed module entry point or configuration store.
 */
class PrivacyGuardHook : LoadPackageHandler {

    override fun onHook() {
        if (!config.privacyBlockMedia &&
            !config.privacyBlockCallLogs &&
            !config.privacyBlockSms &&
            !config.privacyBlockMms &&
            !config.privacyBlockFiles &&
            config.privacyBlockedPaths.none { it.isNotBlank() }
        ) return

        hookContentQueries(ContentResolver::class.java)
        hookContentQueries(ContentProviderClient::class.java)
        hookContentOpens()
        hookFilesystem()
        hookMediaReaders()
    }

    private fun hookContentQueries(clazz: Class<*>) {
        clazz.beforeHookAllMethods("query") { param ->
            val uri = param.args.firstOrNull { it is Uri } as? Uri ?: return@beforeHookAllMethods
            if (!isBlockedUri(uri)) return@beforeHookAllMethods

            val projection = param.args.firstOrNull { it is Array<*> } as? Array<*>
            val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
            param.result = MatrixCursor(columns)
        }
    }

    private fun hookContentOpens() {
        val names = setOf(
            "openInputStream",
            "openFileDescriptor",
            "openAssetFileDescriptor",
            "openTypedAssetFileDescriptor",
            "openFile",
        )

        ContentResolver::class.java.declaredMethods
            .filter { it.name in names && it.parameterTypes.firstOrNull() == Uri::class.java }
            .forEach { method ->
                ModernXposedRuntime.module.hook(method).intercept { chain ->
                    val uri = chain.args.firstOrNull() as? Uri
                    if (uri != null && isBlockedUri(uri)) {
                        throw FileNotFoundException("Guise PrivacyGuard blocked content access")
                    }
                    chain.proceed()
                }
            }

        ContentProviderClient::class.java.declaredMethods
            .filter { it.name in names && it.parameterTypes.firstOrNull() == Uri::class.java }
            .forEach { method ->
                ModernXposedRuntime.module.hook(method).intercept { chain ->
                    val uri = chain.args.firstOrNull() as? Uri
                    if (uri != null && isBlockedUri(uri)) {
                        throw FileNotFoundException("Guise PrivacyGuard blocked provider access")
                    }
                    chain.proceed()
                }
            }
    }

    private fun hookFilesystem() {
        beforeHookConstructor(FileInputStreamCompat.clazz, File::class.java) { param ->
            val path = (param.args.firstOrNull() as? File)?.absolutePath.orEmpty()
            if (isBlockedPath(path)) throw FileNotFoundException("Guise PrivacyGuard blocked file")
        }
        beforeHookConstructor(FileInputStreamCompat.clazz, String::class.java) { param ->
            val path = param.args.firstOrNull() as? String ?: return@beforeHookConstructor
            if (isBlockedPath(path)) throw FileNotFoundException("Guise PrivacyGuard blocked file")
        }

        beforeHookConstructor(RandomAccessFileCompat.clazz, File::class.java, String::class.java) { param ->
            val path = (param.args.firstOrNull() as? File)?.absolutePath.orEmpty()
            if (isBlockedPath(path)) throw FileNotFoundException("Guise PrivacyGuard blocked file")
        }
        beforeHookConstructor(RandomAccessFileCompat.clazz, String::class.java, String::class.java) { param ->
            val path = param.args.firstOrNull() as? String ?: return@beforeHookConstructor
            if (isBlockedPath(path)) throw FileNotFoundException("Guise PrivacyGuard blocked file")
        }

        beforeHookAllMethods(File::class.java, "listFiles") { param ->
            val file = param.thisObject as? File ?: return@beforeHookAllMethods
            if (isBlockedPath(file.absolutePath)) param.result = emptyArray<File>()
        }
        beforeHookAllMethods(File::class.java, "list") { param ->
            val file = param.thisObject as? File ?: return@beforeHookAllMethods
            if (isBlockedPath(file.absolutePath)) param.result = emptyArray<String>()
        }

        setFileVisibilityHook("exists")
        setFileVisibilityHook("isFile")
        setFileVisibilityHook("isDirectory")
        setFileVisibilityHook("canRead")

        ParcelFileDescriptor::class.java.declaredMethods
            .filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.name == "open" &&
                    it.parameterTypes.firstOrNull() == File::class.java
            }
            .forEach { method ->
                ModernXposedRuntime.module.hook(method).intercept { chain ->
                    val file = chain.args.firstOrNull() as? File
                    if (file != null && isBlockedPath(file.absolutePath)) {
                        throw FileNotFoundException("Guise PrivacyGuard blocked descriptor")
                    }
                    chain.proceed()
                }
            }
    }

    private fun setFileVisibilityHook(name: String) {
        beforeHookAllMethods(File::class.java, name) { param ->
            val file = param.thisObject as? File ?: return@beforeHookAllMethods
            if (isBlockedPath(file.absolutePath)) param.result = false
        }
    }

    private fun hookMediaReaders() {
        beforeHookAllMethods(BitmapFactory::class.java, "decodeFile") { param ->
            val path = param.args.firstOrNull() as? String ?: return@beforeHookAllMethods
            if (isBlockedMediaPath(path)) param.result = null
        }

        runCatching {
            Class.forName("android.graphics.ImageDecoder").declaredMethods
                .filter { it.name == "createSource" }
                .forEach { method ->
                    ModernXposedRuntime.module.hook(method).intercept { chain ->
                        val blocked = chain.args.any { arg ->
                            when (arg) {
                                is File -> isBlockedMediaPath(arg.absolutePath)
                                is Uri -> config.privacyBlockMedia && isMediaUri(arg)
                                else -> false
                            }
                        }
                        if (blocked) throw FileNotFoundException("Guise PrivacyGuard blocked image source")
                        chain.proceed()
                    }
                }
        }
    }

    private fun isBlockedUri(uri: Uri): Boolean {
        val authority = uri.authority?.lowercase() ?: return false
        return when {
            authority == ContactsContract.AUTHORITY -> config.passContacts
            authority == "call_log" || authority == "com.android.calllog" -> config.privacyBlockCallLogs
            authority == "sms" || authority == "telephony" -> config.privacyBlockSms
            authority == "mms" || authority == "mms-sms" -> config.privacyBlockMms
            isMediaUri(uri) -> config.privacyBlockMedia ||
                (config.passPhoto && uri.pathSegments.any { it.equals("images", true) }) ||
                (config.passVideo && uri.pathSegments.any { it.equals("video", true) }) ||
                (config.passAudio && uri.pathSegments.any { it.equals("audio", true) })
            else -> config.privacyBlockFiles && config.privacyBlockedPaths.any { uri.toString().contains(it.substringAfterLast('/'), true) }
        }
    }

    private fun isMediaUri(uri: Uri): Boolean {
        val authority = uri.authority?.lowercase() ?: return false
        return authority == "media" ||
            authority == "com.android.providers.media.documents" ||
            authority == "com.google.android.apps.photos.contentprovider" ||
            authority == "com.google.android.apps.photos.content" ||
            authority == "com.google.android.apps.photos.api" ||
            authority == "com.miui.gallery.provider" ||
            authority == "com.sec.android.gallery3d.provider" ||
            authority == "com.oneplus.gallery.provider" ||
            authority == "com.coloros.gallery3d" ||
            authority == "com.huawei.photos"
    }

    private fun isBlockedPath(rawPath: String): Boolean {
        if (rawPath.isBlank()) return false
        if (config.privacyBlockFiles && config.privacyBlockedPaths.any { root ->
                val path = normalize(rawPath)
                val r = normalize(root)
                path == r || path.startsWith("$r/")
            }) return true
        return isBlockedMediaPath(rawPath)
    }

    private fun isBlockedMediaPath(rawPath: String): Boolean {
        if (!config.privacyBlockMedia) return false
        val path = normalize(rawPath)
        val roots = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/Movies",
            "/storage/emulated/0/Recordings",
        )
        return roots.any { root -> path == root || path.startsWith("$root/") }
    }

    private fun normalize(path: String): String =
        path.trim().replace("\\\\", "/").removeSuffix("/")

    private object FileInputStreamCompat {
        val clazz = java.io.FileInputStream::class.java
    }

    private object RandomAccessFileCompat {
        val clazz = java.io.RandomAccessFile::class.java
    }
}
