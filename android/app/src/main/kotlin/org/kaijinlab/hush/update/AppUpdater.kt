package org.kaijinlab.hush.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.kaijinlab.hush.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Public release metadata only. APKs are downloaded on request and installed by Android. */
class AppUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("hush_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    @Volatile private var phase = "idle"
    @Volatile private var error: String? = null
    @Volatile private var received = 0L

    private fun candidate(): GitHubRelease? = prefs.getString("release", null)?.let {
        runCatching { GitHubRelease.parse(JSONObject(it), BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList()) }.getOrNull()
    }

    fun status(): Map<String, Any?> {
        val release = candidate()
        return mapOf(
            "installed" to BuildConfig.VERSION_NAME,
            "automatic" to prefs.getBoolean("automatic", true),
            "checkedAt" to prefs.getLong("checked_at", 0),
            "phase" to phase, "error" to error,
            "available" to (release != null), "version" to release?.version,
            "notes" to release?.notes, "size" to release?.size,
            "received" to received,
            "ready" to (release?.let { File(directory, it.asset).isFile } == true),
            "installAllowed" to context.packageManager.canRequestPackageInstalls()
        )
    }

    fun setAutomatic(value: Boolean): Map<String, Any?> {
        prefs.edit().putBoolean("automatic", value).apply()
        return status()
    }

    suspend fun check(automatic: Boolean): Map<String, Any?> {
        if (!mutex.tryLock()) return status()
        try {
            val elapsed = System.currentTimeMillis() - prefs.getLong("attempted_at", 0)
            if (automatic && (!prefs.getBoolean("automatic", true) || elapsed in 0 until 86_400_000L)) return status()
            phase = "checking"
            error = null
            prefs.edit().putLong("attempted_at", System.currentTimeMillis()).apply()
            val json = withContext(Dispatchers.IO) {
                val connection = connection(GitHubRelease.API)
                try {
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { source ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 2 * 1024 * 1024) { "The release response is too large" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    JSONObject(bytes.toString("UTF-8")).also {
                        GitHubRelease.parse(it, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
                    }
                } finally { connection.disconnect() }
            }
            prefs.edit().putString("release", json.toString()).putLong("checked_at", System.currentTimeMillis()).apply()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failureMessage(failure)
        } finally {
            phase = "idle"
            mutex.unlock()
        }
        return status()
    }

    suspend fun download(): Map<String, Any?> = mutex.withLock {
        val release = candidate() ?: error("Check for updates first")
        phase = "downloading"
        error = null
        received = 0
        val temporary = File(directory, ".download.apk")
        try {
            withContext(Dispatchers.IO) {
                val connection = connection(release.url)
                try {
                    connection.inputStream.use { source ->
                        temporary.outputStream().use { target ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                require(received + count <= release.size) { "The update is larger than expected" }
                                target.write(buffer, 0, count)
                                received += count
                            }
                        }
                    }
                } finally { connection.disconnect() }
                verify(temporary, release)
                // One completed update and one in-progress download at most; no storage permission.
                directory.listFiles()?.filter { it != temporary }?.forEach { it.delete() }
                check(temporary.renameTo(File(directory, release.asset))) { "Could not save the update" }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failureMessage(failure)
        } finally {
            temporary.delete()
            phase = "idle"
        }
        status()
    }

    suspend fun install(activity: Activity): Map<String, Any?> = mutex.withLock {
        val release = candidate() ?: error("Check for updates first")
        val apk = File(directory, release.asset)
        error = null
        try {
            withContext(Dispatchers.IO) { verify(apk, release) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            apk.delete()
            error = failureMessage(failure)
            return@withLock status()
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        status()
    }

    @Suppress("DEPRECATION")
    internal fun verify(file: File, release: GitHubRelease) {
        require(file.isFile && file.length() == release.size) { "Download the update again: the file is incomplete" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "The update checksum does not match" }
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.path, flags) ?: error("The update is not a valid APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == release.version) { "The APK is not this Hush update" }
        fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code(archive) > code(installed)) { "The APK is not newer than the installed version" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "This update requires a newer Android version" }
        fun signatures(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(archive) == trusted) { "The update signing certificate does not match this installation" }
    }

    private fun failureMessage(failure: Exception): String = when (failure) {
        is java.net.SocketTimeoutException -> "The connection timed out. Try again."
        is javax.net.ssl.SSLException -> "Could not securely connect to GitHub"
        is java.io.IOException -> "Could not reach GitHub. Check your connection and try again."
        else -> failure.message ?: "Could not update Hush"
    }

    private fun connection(address: String): HttpURLConnection {
        var url = URL(address)
        val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        repeat(6) {
            require(url.protocol == "https" && url.host in hosts && url.userInfo == null && url.port in setOf(-1, 443)) { "Untrusted update URL" }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Hush/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            if (url.host == "api.github.com") connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = try { connection.responseCode } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(location != null) { "The update redirect is missing" }
                url = URL(url, location)
            } else if (code == 200) {
                return connection
            } else {
                connection.disconnect()
                error(when (code) {
                    403, 429 -> "GitHub is rate-limiting requests. Try again later."
                    404 -> "No published release is available"
                    else -> "GitHub returned HTTP $code. Try again later."
                })
            }
        }
        error("Too many update redirects")
    }
}
