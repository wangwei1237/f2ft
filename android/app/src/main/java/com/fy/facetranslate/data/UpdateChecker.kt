package com.fy.facetranslate.data

import android.os.Build
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** Latest published release on GitHub. */
data class ReleaseInfo(
    val version: String,
    val pageUrl: String,
    /** First few non-empty lines of the release notes. */
    val summary: String,
    /** APK matching this device's CPU architecture, or the universal APK. */
    val apkUrl: String?,
)

sealed interface UpdateResult {
    data class UpToDate(val latest: ReleaseInfo) : UpdateResult
    data class Available(val latest: ReleaseInfo) : UpdateResult
    /** The installed build is not an X.Y.Z release (e.g. a local "1.0" build). */
    data class DevBuild(val latest: ReleaseInfo) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/** Checks GitHub Releases for a newer version. Only called when the user taps "check for updates". */
class UpdateChecker(private val http: OkHttpClient) {

    /** [onResult] runs on an OkHttp thread. */
    fun check(currentVersion: String, onResult: (UpdateResult) -> Unit) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(UpdateResult.Failed(e.message ?: e.javaClass.simpleName))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) return@use UpdateResult.Failed("HTTP ${it.code}")
                    val latest = runCatching { parse(it.body?.string().orEmpty()) }.getOrNull()
                        ?: return@use UpdateResult.Failed("无法解析发布信息")
                    val current = parseVersion(currentVersion)
                    val newest = parseVersion(latest.version)
                    when {
                        current == null -> UpdateResult.DevBuild(latest)
                        newest != null && compare(newest, current) > 0 -> UpdateResult.Available(latest)
                        else -> UpdateResult.UpToDate(latest)
                    }
                }
                onResult(result)
            }
        })
    }

    private fun parse(body: String): ReleaseInfo {
        val json = JSONObject(body)
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets")
        val urls = (0 until (assets?.length() ?: 0)).associate { i ->
            val asset = assets!!.getJSONObject(i)
            asset.getString("name") to asset.getString("browser_download_url")
        }
        val apkUrl = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> urls["f2ft-$version-$abi.apk"] }
            ?: urls["f2ft-$version-universal.apk"]
        val summary = json.optString("body").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("|") && !it.startsWith("#") }
            .take(3)
            .joinToString("\n")
        return ReleaseInfo(version, json.getString("html_url"), summary, apkUrl)
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/wangwei1237/f2ft/releases/latest"
        const val PROJECT_URL = "https://github.com/wangwei1237/f2ft"

        /** Parses "X.Y.Z" into three numbers, or null for any other format. */
        fun parseVersion(v: String): List<Int>? =
            v.split(".").takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }

        private fun compare(a: List<Int>, b: List<Int>): Int =
            a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
    }
}
