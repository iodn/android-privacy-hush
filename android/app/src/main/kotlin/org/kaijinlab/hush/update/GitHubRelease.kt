package org.kaijinlab.hush.update

import org.json.JSONObject

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class GitHubRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val asset: String,
    val size: Long,
    val sha256: String,
    val url: String
) {
    companion object {
        const val REPOSITORY = "iodn/android-privacy-hush"
        const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private val ABIS = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86_64" to "x86_64")
        const val MAX_APK_BYTES = 200L * 1024 * 1024

        fun parse(json: JSONObject, installed: String, abis: List<String>): GitHubRelease? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            val version = AppVersion.parse(tag) ?: error("The release has an unsupported version number")
            val current = AppVersion.parse(installed) ?: error("Could not read the installed version")
            if (version <= current) return null
            val names = abis.mapNotNull { ABIS[it] }.map { "Hush-${tag.removePrefix("v")}-$it.apk" }
            require(names.isNotEmpty()) { "This device architecture is not supported" }
            val preferred = names + "Hush-${tag.removePrefix("v")}-universal.apk"
            val assets = json.getJSONArray("assets")
            val asset = preferred.firstNotNullOfOrNull { name ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
            } ?: error("This release does not contain a compatible APK")
            val name = asset.getString("name")
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_BYTES) { "The update has an invalid size" }
            val digest = asset.getString("digest")
            require(Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) { "The update is missing its SHA-256 checksum" }
            val url = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
            require(asset.getString("browser_download_url") == url) { "The update download is not from this repository" }
            return GitHubRelease(tag, tag.removePrefix("v"), json.optString("body").take(32_000), name, size, digest.substringAfter(':').lowercase(), url)
        }
    }
}
