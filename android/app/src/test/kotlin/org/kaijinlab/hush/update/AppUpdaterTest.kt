package org.kaijinlab.hush.update

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Suppress("DEPRECATION")
class AppUpdaterTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var file: File
    private lateinit var archive: PackageInfo
    private lateinit var candidate: GitHubRelease
    private lateinit var updater: AppUpdater
    private fun signer(info: PackageInfo, value: String) {
        val signature = Signature(value)
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = ReflectionHelpers.newInstance(SigningInfo::class.java).also { shadowOf(it).setSignatures(arrayOf(signature)) }
        } else info.signatures = arrayOf(signature)
    }
    @Before fun setup() {
        context.getSharedPreferences("hush_updates", 0).edit().clear().commit()
        file = File(context.cacheDir, "verification-test.apk").apply { writeText("test APK payload") }
        val installed = PackageInfo().apply {
            packageName = context.packageName; versionCode = 7; versionName = "1.5.0"
            if (Build.VERSION.SDK_INT >= 28) longVersionCode = 7
            signer(this, "001122")
            applicationInfo = ApplicationInfo().apply { packageName = context.packageName; minSdkVersion = 26 }
        }
        shadowOf(context.packageManager).installPackage(installed)
        archive = PackageInfo().apply {
            packageName = context.packageName; versionCode = 8; versionName = "1.6.0"
            if (Build.VERSION.SDK_INT >= 28) longVersionCode = 8
            signer(this, "001122")
            applicationInfo = ApplicationInfo().apply { packageName = context.packageName; minSdkVersion = 26 }
        }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.path, archive)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        candidate = GitHubRelease("v1.6.0", "1.6.0", "Fixes", "Hush-1.6.0-arm64.apk", file.length(), hash, "https://github.com/iodn/android-privacy-hush/releases/download/v1.6.0/Hush-1.6.0-arm64.apk")
        updater = AppUpdater(context)
    }
    @Test fun `matching signed newer APK is accepted`() { updater.verify(file, candidate) }
    @Test @Config(sdk = [26]) fun `Android 8 checks the legacy signing certificate`() { updater.verify(file, candidate) }
    @Test fun `truncated download is refused`() {
        file.writeText("short")
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `incorrect SHA256 is refused`() {
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate.copy(sha256 = "0".repeat(64))) }
    }
    @Test fun `another package is refused`() {
        archive.packageName = "other.application"
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `a different signer is refused`() {
        signer(archive, "aabbcc")
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `a lower build number is refused even if the tag is newer`() {
        archive.longVersionCode = 6
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `a different APK version is refused`() {
        archive.versionName = "1.7.0"
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `incompatible Android minimum is refused`() {
        archive.applicationInfo!!.minSdkVersion = 99
        assertThrows(IllegalArgumentException::class.java) { updater.verify(file, candidate) }
    }
    @Test fun `an invalid cached APK can be downloaded again after installation is refused`() = runBlocking {
        val json = org.json.JSONObject().put("tag_name", "v1.6.0").put("draft", false).put("prerelease", false)
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", "Hush-1.6.0-universal.apk").put("size", candidate.size)
                .put("digest", "sha256:" + candidate.sha256)
                .put("browser_download_url", "https://github.com/iodn/android-privacy-hush/releases/download/v1.6.0/Hush-1.6.0-universal.apk")))
        context.getSharedPreferences("hush_updates", 0).edit().putString("release", json.toString()).commit()
        val cached = File(context.cacheDir, "updates/Hush-1.6.0-universal.apk")
        cached.parentFile!!.mkdirs()
        cached.writeText("truncated")
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).get()
        val status = updater.install(activity)
        assertEquals(false, status["ready"])
        assertNotNull(status["error"])
        assertFalse(cached.exists())
    }

    @Test fun `automatic checks can be disabled without contacting the network`() = runBlocking {
        updater.setAutomatic(false)
        val status = updater.check(automatic = true)
        assertEquals(false, status["automatic"])
        assertEquals(0L, status["checkedAt"])
        assertNull(status["error"])
    }
    @Test fun `automatic checks are limited to once per day`() = runBlocking {
        context.getSharedPreferences("hush_updates", 0).edit().putLong("attempted_at", System.currentTimeMillis()).commit()
        assertNull(updater.check(automatic = true)["error"])
    }
}
