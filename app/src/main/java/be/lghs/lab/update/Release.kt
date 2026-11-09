package be.lghs.lab.update

import org.json.JSONObject

/** Release GitHub publiée par le workflow android.yml (tag vX.Y.Z + un APK). */
data class Release(val tag: String, val versionCode: Int, val apkUrl: String) {

    companion object {
        /** vX.Y.Z → versionCode, même formule que app/build.gradle.kts. */
        fun versionCode(tag: String): Int? =
            Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(tag)?.destructured
                ?.let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }

        /** Réponse de `/releases/latest` → release installable, ou null (brouillon, tag invalide, pas d'APK). */
        fun parse(json: JSONObject): Release? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val code = versionCode(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return Release(tag, code, apk.getString("browser_download_url"))
        }
    }
}
