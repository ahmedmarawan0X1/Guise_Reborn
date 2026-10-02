package com.houvven.guise.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.houvven.guise.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrls: List<String>,
    val apkSha256: String,
)

data class UpdateDownloadProgress(
    val fraction: Float?,
    val active: Boolean,
    val successful: Boolean,
)

private const val RELEASE_MANIFEST = "latest-release.json"
private const val PRERELEASE_MANIFEST = "latest-prerelease.json"

internal fun updateManifestNames(versionName: String, prerelease: Boolean = '-' in versionName): List<String> =
    if (prerelease) {
        listOf(PRERELEASE_MANIFEST, RELEASE_MANIFEST)
    } else {
        listOf(RELEASE_MANIFEST)
    }

private class UpdateChannelException(message: String) : IllegalStateException(message)

internal fun validateUpdateChannel(actualPrerelease: Boolean?, expectedPrerelease: Boolean) {
    if (actualPrerelease == null) {
        throw UpdateChannelException("Missing update channel")
    }
    if (actualPrerelease != expectedPrerelease) {
        throw UpdateChannelException("Mismatched update channel")
    }
}

class AppUpdater {
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        coroutineScope {
            val attempts = updateManifestNames(BuildConfig.VERSION_NAME, BuildConfig.IS_PRERELEASE).map { manifestName ->
                async { runCatching { fetchManifest(manifestName) } }
            }
            val results = attempts.awaitAll()
            results.mapNotNull(Result<UpdateInfo>::getOrNull)
                .maxByOrNull(UpdateInfo::versionCode)
                ?: throw IllegalStateException(
                    results.mapNotNull(Result<UpdateInfo>::exceptionOrNull)
                        .lastOrNull()?.message,
                )
        }
    }

    private suspend fun fetchManifest(manifestName: String): UpdateInfo = coroutineScope {
        val expectedPrerelease = manifestName == PRERELEASE_MANIFEST
        val attempts = updateSources(manifestName).map { source ->
            async { runCatching { fetch(source, expectedPrerelease) } }
        }

        // GitHub is authoritative for each channel. Mirrors are started at the same time so a
        // GitHub failure can immediately fall back without making the user wait serially.
        val authoritative = attempts.first().await()
        authoritative.getOrNull()?.let { info ->
            attempts.drop(1).forEach { it.cancel() }
            return@coroutineScope info
        }
        authoritative.exceptionOrNull()
            ?.takeIf { it is UpdateChannelException }
            ?.let { error ->
                attempts.drop(1).forEach { it.cancel() }
                throw error
            }

        val fallbacks = attempts.drop(1).awaitAll()
        fallbacks.mapNotNull(Result<UpdateInfo>::getOrNull)
            .maxByOrNull(UpdateInfo::versionCode)
            ?.let { return@coroutineScope it }

        throw IllegalStateException(
            fallbacks.mapNotNull(Result<UpdateInfo>::exceptionOrNull)
                .lastOrNull()?.message
                ?: authoritative.exceptionOrNull()?.message,
        )
    }

    fun isNewer(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    fun download(context: Context, info: UpdateInfo): Long {
        check(info.apkUrls.isNotEmpty()) {
            context.getString(com.houvven.guise.R.string.update_download_no_apk)
        }
        return enqueueDownload(
            context = context,
            urls = info.apkUrls,
            sourceIndex = 0,
            expectedSha256 = info.apkSha256,
            expectedVersionCode = info.versionCode,
        )
    }

    fun retryNextDownload(context: Context, failedDownloadId: Long): Long? =
        synchronized(DOWNLOAD_PLAN_LOCK) {
            val preferences = downloadPreferences(context)
            val currentDownloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
            if (currentDownloadId != failedDownloadId) {
                return@synchronized currentDownloadId.takeIf { it >= 0L }
            }
            val urls = readDownloadUrls(preferences.getString(KEY_DOWNLOAD_URLS, null))
            val firstNextIndex = preferences.getInt(KEY_DOWNLOAD_SOURCE_INDEX, 0) + 1
            if (firstNextIndex !in urls.indices) return@synchronized null
            runCatching {
                context.getSystemService(DownloadManager::class.java).remove(failedDownloadId)
            }
            val expectedSha256 = preferences.getString(KEY_DOWNLOAD_SHA256, null).orEmpty()
            val expectedVersionCode = preferences.getInt(KEY_DOWNLOAD_VERSION_CODE, -1)
            (firstNextIndex..urls.lastIndex).firstNotNullOfOrNull { sourceIndex ->
                runCatching {
                    enqueueDownload(
                        context = context,
                        urls = urls,
                        sourceIndex = sourceIndex,
                        expectedSha256 = expectedSha256,
                        expectedVersionCode = expectedVersionCode,
                    )
                }.getOrNull()
            }
        }

    fun expectedSha256(context: Context): String =
        downloadPreferences(context).getString(KEY_DOWNLOAD_SHA256, null).orEmpty()

    private fun enqueueDownload(
        context: Context,
        urls: List<String>,
        sourceIndex: Int,
        expectedSha256: String,
        expectedVersionCode: Int,
    ): Long {
        val url = urls[sourceIndex]
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(com.houvven.guise.R.string.app_name))
            .setDescription(context.getString(com.houvven.guise.R.string.update_downloading))
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
        return manager.enqueue(request).also { id ->
            downloadPreferences(context)
                .edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_URLS, JSONArray(urls).toString())
                .putInt(KEY_DOWNLOAD_SOURCE_INDEX, sourceIndex)
                .putString(KEY_DOWNLOAD_SHA256, expectedSha256)
                .putInt(KEY_DOWNLOAD_VERSION_CODE, expectedVersionCode)
                .remove(KEY_READY_DOWNLOAD_ID)
                .remove(KEY_READY_VERSION_CODE)
                .remove(KEY_READY_SHA256)
                .apply()
        }
    }

    suspend fun downloadProgress(context: Context, downloadId: Long): UpdateDownloadProgress? =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(DownloadManager::class.java)
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                    )
                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                        ),
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    UpdateDownloadProgress(
                        fraction = if (total > 0L) {
                            (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        active = status == DownloadManager.STATUS_PENDING ||
                            status == DownloadManager.STATUS_RUNNING ||
                            status == DownloadManager.STATUS_PAUSED,
                        successful = status == DownloadManager.STATUS_SUCCESSFUL,
                    )
                }
            }.getOrNull()
        }

    private fun fetch(source: String, expectedPrerelease: Boolean): UpdateInfo {
        val separator = if ('?' in source) '&' else '?'
        val connection = URL("$source${separator}t=${System.currentTimeMillis()}")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "Guise/${BuildConfig.VERSION_CODE}")
        return try {
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode}"
            }
            var json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("encoding").equals("base64", ignoreCase = true)) {
                val decoded = Base64.decode(json.getString("content"), Base64.DEFAULT)
                json = JSONObject(decoded.toString(Charsets.UTF_8))
            }
            parse(json, expectedPrerelease)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject, expectedPrerelease: Boolean): UpdateInfo {
        validateUpdateChannel(
            actualPrerelease = json.takeIf { it.has("prerelease") }?.getBoolean("prerelease"),
            expectedPrerelease = expectedPrerelease,
        )
        val tag = json.optString("tag")
        val versionName = json.optString("versionName")
            .ifBlank { json.optString("version") }
            .ifBlank { tag.removePrefix("v") }
        val versionCode = json.optInt("versionCode", -1)
        check(versionCode > 0) { "Invalid versionCode" }
        check(versionName.isNotBlank()) { "Missing versionName" }
        val releaseUrl = json.optString("releaseUrl")
            .ifBlank { RELEASES_URL }
        check(releaseUrl.startsWith("https://")) { "Invalid releaseUrl" }
        val apkUrls = buildList {
            json.optJSONArray("apkUrls")?.let { urls ->
                repeat(urls.length()) { index ->
                    urls.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            json.optString("apkUrl").takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        check(apkUrls.all { it.startsWith("https://") }) { "Invalid APK URL" }
        val apkSha256 = json.optString("apkSha256").lowercase()
        check(apkSha256.isBlank() || SHA256_PATTERN.matches(apkSha256)) {
            "Invalid APK SHA-256"
        }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            title = json.optString("title").ifBlank {
                json.optString("name").ifBlank { tag }
            },
            notes = json.optString("notes").trim(),
            releaseUrl = releaseUrl,
            apkUrls = apkUrls,
            apkSha256 = apkSha256,
        )
    }

    private fun downloadPreferences(context: Context) =
        context.applicationContext.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)

    private fun readDownloadUrls(value: String?): List<String> = runCatching {
        val json = JSONArray(value.orEmpty())
        buildList {
            repeat(json.length()) { index ->
                json.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val RELEASES_URL = "https://github.com/daxiaamu/Guise_Reborn/releases"
        private const val MANIFEST_REPOSITORY_PATH = "daxiaamu/Guise_Reborn@main"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 8_000
        const val UPDATE_PREFERENCES = "app_update"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_READY_DOWNLOAD_ID = "ready_download_id"
        const val KEY_DOWNLOAD_URLS = "download_urls"
        const val KEY_DOWNLOAD_SOURCE_INDEX = "download_source_index"
        const val KEY_DOWNLOAD_SHA256 = "download_sha256"
        const val KEY_DOWNLOAD_VERSION_CODE = "download_version_code"
        const val KEY_READY_VERSION_CODE = "ready_version_code"
        const val KEY_READY_SHA256 = "ready_sha256"
        const val APK_MIME = "application/vnd.android.package-archive"
        private val DOWNLOAD_PLAN_LOCK = Any()
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private fun updateSources(manifestName: String) = listOf(
            "https://api.github.com/repos/daxiaamu/Guise_Reborn/contents/$manifestName?ref=main",
            "https://cdn.jsdelivr.net/gh/$MANIFEST_REPOSITORY_PATH/$manifestName",
            "https://fastly.jsdelivr.net/gh/$MANIFEST_REPOSITORY_PATH/$manifestName",
            "https://gcore.jsdelivr.net/gh/$MANIFEST_REPOSITORY_PATH/$manifestName",
            "https://raw.githubusercontent.com/daxiaamu/Guise_Reborn/main/$manifestName",
        )
    }
}

object UpdatePromptPreferences {
    private const val PREFERENCES = "app_update"
    private const val KEY_IGNORED_VERSION = "ignored_version_code"

    fun ignore(context: Context, versionCode: Int) {
        preferences(context).edit().putInt(KEY_IGNORED_VERSION, versionCode).apply()
    }

    fun isIgnored(context: Context, versionCode: Int): Boolean =
        preferences(context).getInt(KEY_IGNORED_VERSION, 0) >= versionCode

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
