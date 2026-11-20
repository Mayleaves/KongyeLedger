package com.kongye.ledger

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal class LedgerUpdates(private val client: OkHttpClient = transport) {
    fun check(installedVersion: String): JSONObject {
        val request = Request.Builder().url("https://api.github.com/repos/$REPOSITORY/releases?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KongyeLedger")
            .build()
        val releases = client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                throw LedgerUpdateException("GitHub 暂时限制了查询次数，请稍后重试")
            }
            if (!response.isSuccessful) throw LedgerUpdateException("GitHub 暂时无法提供版本记录，请稍后重试")
            val source = response.body?.source() ?: throw LedgerUpdateException("GitHub 未返回版本记录")
            if (source.request(2_097_153L)) throw LedgerUpdateException("GitHub 版本记录过大，请打开发布页查看")
            JSONArray(source.readUtf8())
        }
        val published = (0 until releases.length()).mapNotNull { index ->
            val entry = releases.optJSONObject(index) ?: return@mapNotNull null
            val tag = entry.optString("tag_name")
            val version = Version.parse(tag) ?: return@mapNotNull null
            if (entry.optBoolean("draft") || entry.optBoolean("prerelease")) return@mapNotNull null
            val apkUrl = githubUrl("apk", tag)
            val assets = entry.optJSONArray("assets") ?: JSONArray()
            val hasApk = (0 until assets.length()).any { assetIndex ->
                val asset = assets.optJSONObject(assetIndex) ?: return@any false
                asset.optString("state") == "uploaded" && asset.optString("browser_download_url") == apkUrl
            }
            version to JSONObject()
                .put("tag", tag)
                .put("title", entry.optString("name").takeIf { it.isNotBlank() && !entry.isNull("name") }?.take(120) ?: tag)
                .put("date", runCatching {
                    Instant.parse(entry.optString("published_at")).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                }.getOrDefault(""))
                .put("notes", if (entry.isNull("body")) "" else entry.optString("body").take(12_000))
                .put("hasApk", hasApk)
                .put("github", true)
        }.sortedByDescending { it.first }

        val current = Version.parse(installedVersion)
        val latest = published.firstOrNull()
        val comparison = if (current != null && latest != null) latest.first.compareTo(current) else null
        return JSONObject()
            .put("comparison", comparison ?: JSONObject.NULL)
            .put("releases", JSONArray(published.map { it.second }))
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        companion object {
            fun parse(value: String): Version? {
                val parts = versionTag.matchEntire(value)?.groupValues ?: return null
                return Version(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
            }
        }
    }

    companion object {
        private const val REPOSITORY = "Mayleaves/KongyeLedger"
        private const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
        private val versionTag = Regex("v?([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})")
        private val transport = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        fun githubUrl(page: String, tag: String): String? = when (page) {
            "repository" -> REPOSITORY_URL
            "releases" -> "$REPOSITORY_URL/releases"
            "release" -> if (versionTag.matches(tag)) "$REPOSITORY_URL/releases/tag/$tag" else null
            "apk" -> if (versionTag.matches(tag)) "$REPOSITORY_URL/releases/download/$tag/KongyeLedger-$tag.apk" else null
            else -> null
        }
    }
}

internal class LedgerUpdateException(message: String) : IOException(message)
