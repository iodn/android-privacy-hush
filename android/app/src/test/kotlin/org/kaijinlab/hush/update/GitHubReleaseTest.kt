package org.kaijinlab.hush.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitHubReleaseTest {
    private fun release(tag: String = "v1.6.0", architectures: List<String> = listOf("arm", "arm64", "x86_64", "universal")): JSONObject {
        val assets = architectures.map { arch ->
            val name = "Hush-${tag.removePrefix("v")}-$arch.apk"
            JSONObject().put("name", name).put("size", 1000)
                .put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url", "https://github.com/iodn/android-privacy-hush/releases/download/$tag/$name")
        }
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("body", "Improved privacy controls").put("assets", JSONArray(assets))
    }
    private fun parse(json: JSONObject) = GitHubRelease.parse(json, "1.5.0", listOf("arm64-v8a", "armeabi-v7a"))

    @Test fun `new stable release keeps notes and prefers native arm64`() {
        val selected = parse(release())!!
        assertEquals("Hush-1.6.0-arm64.apk", selected.asset)
        assertEquals("1.6.0", selected.version)
        assertEquals("Improved privacy controls", selected.notes)
    }
    @Test fun `all release architectures select their matching APK`() {
        for ((abi, name) in mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86_64" to "x86_64")) {
            assertEquals("Hush-1.6.0-$name.apk", GitHubRelease.parse(release(), "1.5.0", listOf(abi))!!.asset)
        }
    }
    @Test fun `universal fallback works but unsupported x86 is refused`() {
        assertEquals("Hush-1.6.0-universal.apk", parse(release(architectures = listOf("universal")))!!.asset)
        assertThrows(IllegalArgumentException::class.java) { GitHubRelease.parse(release(), "1.5.0", listOf("x86")) }
    }
    @Test fun `current or older versions never offer a downgrade`() {
        assertNull(parse(release("v1.5.0")))
        assertNull(parse(release("v1.4.9")))
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.9")!!)
    }
    @Test fun `draft and prerelease are not offered`() {
        assertNull(parse(release().put("draft", true)))
        assertNull(parse(release().put("prerelease", true)))
    }
    @Test fun `version parser rejects malformed beta and overflowing components`() {
        for (value in listOf("latest", "1.6", "v1.6.0-beta", "v99999999999999999.0.0")) assertNull(AppVersion.parse(value))
    }
    @Test fun `missing architecture has a clear failure`() {
        assertThrows(IllegalStateException::class.java) { parse(release(architectures = listOf("x86_64"))) }
    }
    @Test fun `an arbitrary repository or insecure download URL is refused`() {
        for (url in listOf("https://example.org/update.apk", "http://github.com/iodn/android-privacy-hush/a.apk", "https://github.com/attacker/repo/update.apk")) {
            val json = release(architectures = listOf("arm64"))
            json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertThrows(IllegalArgumentException::class.java) { parse(json) }
        }
    }
    @Test fun `missing or malformed digest is refused`() {
        for (value in listOf("", "sha1:" + "a".repeat(40), "sha256:broken")) {
            val json = release(architectures = listOf("arm64"))
            json.getJSONArray("assets").getJSONObject(0).put("digest", value)
            assertThrows(IllegalArgumentException::class.java) { parse(json) }
        }
    }
    @Test fun `oversized and empty assets are refused`() {
        for (size in listOf(0L, GitHubRelease.MAX_APK_BYTES + 1)) {
            val json = release(architectures = listOf("arm64"))
            json.getJSONArray("assets").getJSONObject(0).put("size", size)
            assertThrows(IllegalArgumentException::class.java) { parse(json) }
        }
    }
}
