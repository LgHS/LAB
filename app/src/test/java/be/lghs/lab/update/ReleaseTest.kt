package be.lghs.lab.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Format de `/repos/LgHS/LAB/releases/latest` (champs utilisés uniquement). */
class ReleaseTest {
    private fun release(tag: String = "v0.4.0", draft: Boolean = false, prerelease: Boolean = false, assets: String = APK) =
        JSONObject("""{"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "assets": [$assets]}""")

    @Test
    fun versionCodeMatchesGradleFormula() {
        assertEquals(400, Release.versionCode("v0.4.0"))
        assertEquals(301, Release.versionCode("0.3.1"))
        assertEquals(10_203, Release.versionCode("v1.2.3"))
        assertNull(Release.versionCode("v0.4"))
        assertNull(Release.versionCode("nightly"))
    }

    @Test
    fun parsesApkAsset() {
        val r = Release.parse(release(assets = """{"name": "notes.txt", "browser_download_url": "x"}, $APK"""))!!
        assertEquals("v0.4.0", r.tag)
        assertEquals(400, r.versionCode)
        assertEquals("https://github.com/LgHS/LAB/releases/download/v0.4.0/LittleAndroidBrother-0.4.0.apk", r.apkUrl)
    }

    @Test
    fun ignoresUnusableReleases() {
        assertNull(Release.parse(release(draft = true)))
        assertNull(Release.parse(release(prerelease = true)))
        assertNull(Release.parse(release(tag = "test")))
        assertNull(Release.parse(release(assets = "")))
    }

    private companion object {
        const val APK = """{"name": "LittleAndroidBrother-0.4.0.apk",
            "browser_download_url": "https://github.com/LgHS/LAB/releases/download/v0.4.0/LittleAndroidBrother-0.4.0.apk"}"""
    }
}
