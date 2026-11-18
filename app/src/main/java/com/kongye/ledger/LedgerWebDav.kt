package com.kongye.ledger

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_BACKUP_BYTES = 40 * 1024 * 1024
private const val BACKUP_FOLDER = "KongyeLedger"
private const val BACKUP_FILE = "kongye-ledger-backup.json"

internal class LedgerDavAccount(address: String, val user: String, val password: String) {
    val directory: HttpUrl

    init {
        val url = address.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "请输入 HTTPS WebDAV 目录地址，账号和密码分开填写" }
        require(':' !in user) { "用户名不能包含冒号" }
        require(user.isNotEmpty() || password.isEmpty()) { "请填写 WebDAV 用户名" }
        directory = if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }

    val backupDirectory: HttpUrl
        get() = if (directory.pathSegments.dropLast(1).lastOrNull() == BACKUP_FOLDER) directory
        else directory.newBuilder().addPathSegment(BACKUP_FOLDER).addPathSegment("").build()

    val file: HttpUrl get() = backupDirectory.newBuilder().addPathSegment(BACKUP_FILE).build()
    val legacyFile: HttpUrl? get() =
        if (directory == backupDirectory) null else directory.newBuilder().addPathSegment(BACKUP_FILE).build()
}

internal class LedgerWebDav(private val client: OkHttpClient = transport) {
    fun test(account: LedgerDavAccount) {
        probe(account, account.directory).use { response -> checkResponse(response, "连接") }
    }

    private fun probe(account: LedgerDavAccount, directory: HttpUrl): Response {
        val properties = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val request = request(account, directory)
            .header("Depth", "0")
            .method("PROPFIND", properties.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request).execute()
    }

    private fun ensureBackupDirectory(account: LedgerDavAccount) {
        probe(account, account.backupDirectory).use { response ->
            if (response.isSuccessful) return
            if (response.code != 404) checkResponse(response, "检查备份目录")
        }
        val create = request(account, account.backupDirectory).method("MKCOL", null).build()
        client.newCall(create).execute().use { response ->
            if (response.isSuccessful) return
            if (response.code != 405) checkResponse(response, "创建备份目录")
        }
        // Another client may have created the collection between PROPFIND and MKCOL.
        probe(account, account.backupDirectory).use { response -> checkResponse(response, "检查备份目录") }
    }

    fun upload(account: LedgerDavAccount, backup: String) {
        val bytes = backup.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "备份文件超过 40 MB" }
        ensureBackupDirectory(account)
        val request = request(account, account.file)
            .put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response -> checkResponse(response, "上传") }
    }

    fun download(account: LedgerDavAccount): String =
        downloadFile(account, account.file) ?: account.legacyFile?.let { downloadFile(account, it) }
        ?: throw LedgerDavException("下载失败 · 该目录还没有空叶记账备份")

    private fun downloadFile(account: LedgerDavAccount, url: HttpUrl): String? {
        val request = request(account, url).get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            checkResponse(response, "下载")
            val body = requireNotNull(response.body) { "服务器没有返回备份文件" }
            require(body.contentLength() <= MAX_BACKUP_BYTES) { "备份文件超过 40 MB" }
            val source = body.source()
            require(!source.request(MAX_BACKUP_BYTES.toLong() + 1)) { "备份文件超过 40 MB" }
            source.readUtf8()
        }
    }

    private fun request(account: LedgerDavAccount, url: HttpUrl): Request.Builder =
        Request.Builder().url(url).apply {
            if (account.user.isNotEmpty()) {
                header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
            }
        }

    private fun checkResponse(response: Response, operation: String) {
        if (response.isSuccessful) return
        val message = when (response.code) {
            in 300..399 -> "服务器要求跳转，请填写实际 WebDAV 目录"
            401, 403 -> "账号、应用密码或目录权限有误"
            404 -> if (operation == "下载") "该目录还没有空叶记账备份" else "WebDAV 目录不存在"
            405 -> "此地址不支持所需的 WebDAV 操作"
            409 -> "请先在服务器创建备份目录"
            507 -> "服务器空间不足"
            else -> "服务器返回 HTTP ${response.code}"
        }
        throw LedgerDavException("${operation}失败 · $message")
    }

    companion object {
        private val transport = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

internal class LedgerDavException(message: String) : IOException(message)
