package com.shilapi.xcertplay.update

import java.net.URI
import java.util.Locale
import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"
    private const val DOWNLOAD_HOST = "github.com"
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val ALLOWED_HOSTS = setOf(
        "github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )

    /**
     * A release asset may only live on GitHub's own hosts over plain HTTPS: no user info, no odd
     * port. Both the catalog and the downloader call this, so a replaced release JSON cannot point
     * the installer at an arbitrary server even when its SHA256SUMS file is self-consistent.
     */
    internal fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && host in ALLOWED_HOSTS &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }

    private fun isDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return isAllowedUrl(url) && uri.host.equals(DOWNLOAD_HOST, ignoreCase = true) && uri.port == -1
    }

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: return null
            var apkName = ""
            var apkUrl = ""
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!isDownloadUrl(url)) continue
                if (APK_NAME.matches(name)) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) checksumsUrl = url
            }
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) return null
            return UpdateRelease(release.optString("tag_name"), apkName, apkUrl, checksumsUrl)
        }
        return null
    }
}
