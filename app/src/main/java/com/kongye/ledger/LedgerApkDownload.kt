package com.kongye.ledger

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LedgerUpdateFileProvider : FileProvider()

internal data class LedgerApkAsset(val tag: String, val url: String, val size: Long, val sha256: String)

internal class LedgerApkDownload(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }

    fun download(asset: LedgerApkAsset, progress: (Long, Long) -> Unit): File {
        require(LedgerUpdates.githubUrl("apk", asset.tag) == asset.url) { "更新下载地址无效" }
        require(asset.size in 1..209_715_200 && Regex("[a-fA-F0-9]{64}").matches(asset.sha256)) { "更新文件缺少完整性信息" }
        val directory = File(context.filesDir, "updates").apply { check(isDirectory || mkdirs()) { "无法创建更新目录" } }
        val partial = File(directory, "download.apk.part")
        val target = File(directory, "KongyeLedger-${asset.tag}.apk")
        val call = client.newCall(Request.Builder().url(asset.url).header("User-Agent", "KongyeLedger").build())
        active = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw LedgerUpdateException("下载失败（${response.code}），请重试")
                if (!response.request.url.isHttps) throw LedgerUpdateException("下载连接不安全，请重试")
                val body = response.body ?: throw LedgerUpdateException("服务器未返回安装包")
                if (body.contentLength() >= 0 && body.contentLength() != asset.size) throw LedgerUpdateException("安装包大小不符，请重新下载")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = 0L
                progress(0, asset.size)
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        if (call.isCanceled()) throw java.io.IOException("下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.size) throw LedgerUpdateException("安装包大小不符，请重新下载")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 200 || received == asset.size) {
                            progress(received, asset.size)
                            lastProgress = now
                        }
                    }
                } }
                if (received != asset.size || digest.digest().joinToString("") { "%02x".format(it) } != asset.sha256.lowercase()) {
                    throw LedgerUpdateException("安装包不完整或校验失败，请重新下载")
                }
                if (!partial.renameTo(target)) throw LedgerUpdateException("保存安装包失败，请重试")
                return target
            }
        } finally {
            partial.delete()
            active = null
        }
    }
}

@Suppress("DEPRECATION")
internal fun verifyLedgerUpdateApk(context: Context, file: File) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw LedgerUpdateException("系统无法读取安装包，请重新下载")
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    if (archive.packageName != context.packageName) throw LedgerUpdateException("安装包不属于空叶记账")
    fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
    val actual = certificates(archive)
    if (actual.isEmpty() || actual != certificates(installed)) throw LedgerUpdateException("安装包签名与当前版本不一致，无法覆盖安装")
    val next = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
    if (next < current) throw LedgerUpdateException("不能安装比当前版本更旧的安装包")
}

internal fun ledgerUpdateInstallIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("空叶记账更新", uri) }
}
