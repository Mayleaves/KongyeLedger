package com.kongye.ledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val LEDGER_PAGE = "https://appassets.androidplatform.net/assets/ledger.html"
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val channels = mapOf(
    "wechat" to "com.tencent.mm",
    "alipay" to "com.eg.android.AlipayGphone",
    "unionpay" to "com.unionpay"
)

internal fun Store.notificationChannelEnabled(packageName: String): Boolean =
    setting("channel:$packageName", "true") == "true"

@Composable
internal fun LedgerWebScreen(activity: MainActivity, store: Store) {
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val pageAttempt = attempt
    val revision by store.revision.collectAsState()
    val entry = activity.entryIntent
    var davInProgress by remember { mutableStateOf(false) }

    fun script(code: String) { web?.evaluateJavascript(code, null) }
    fun result(message: String) = script("syncLedger(); toast(${JSONObject.quote(message)});")
    fun runWebDav(operation: String, address: String, user: String, password: String) {
        if (davInProgress) return
        davInProgress = true
        scope.launch {
            try {
                val (success, message) = withContext(Dispatchers.IO) {
                    val account = try {
                        LedgerDavAccount(address, user.trim(), password)
                    } catch (e: IllegalArgumentException) {
                        return@withContext false to (e.message ?: "请检查 WebDAV 设置")
                    }
                    try {
                        val dav = LedgerWebDav()
                        val message = when (operation) {
                            "test" -> { dav.test(account); "连接成功" }
                            "upload" -> { dav.upload(account, store.backup()); "已上传 WebDAV 备份" }
                            "restore" -> {
                                val contents = dav.download(account)
                                try {
                                    store.restore(contents)
                                } catch (_: org.json.JSONException) {
                                    throw LedgerDavException("下载的文件不是有效的空叶记账备份")
                                } catch (_: IllegalArgumentException) {
                                    throw LedgerDavException("下载的文件不是有效的空叶记账备份")
                                }
                                "已从 WebDAV 恢复账本"
                            }
                            else -> return@withContext false to "不支持此操作"
                        }
                        activity.getSharedPreferences("ledger_webdav", Context.MODE_PRIVATE).edit()
                            .putString("address", account.directory.toString())
                            .putString("user", account.user)
                            .apply()
                        true to message
                    } catch (e: LedgerDavException) {
                        false to (e.message ?: "WebDAV 操作失败")
                    } catch (e: IllegalArgumentException) {
                        false to (e.message ?: "请检查备份文件")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: javax.net.ssl.SSLException) {
                        false to "无法验证服务器的 HTTPS 连接"
                    } catch (_: java.net.SocketTimeoutException) {
                        false to "连接超时，请检查网络和服务器"
                    } catch (_: Exception) {
                        false to "WebDAV 操作失败，请检查地址、网络和目录权限"
                    }
                }
                script("finishWebDav($success,${JSONObject.quote(message)});")
            } finally {
                davInProgress = false
            }
        }
    }
    fun export(uri: Uri?, csv: Boolean) {
        if (uri == null) return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val contents = if (csv) ledgerCsv(store.txns()) else store.backup()
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(contents.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            result(if (outcome.isSuccess) "已导出文件" else "导出失败，请重试")
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, false) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, true) }
    var importing by remember { mutableStateOf("json") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val format = importing
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 40 * 1024 * 1024) { "文件过大" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if (format == "json") {
                            store.restore(bytes.toString(Charsets.UTF_8))
                            "已恢复备份"
                        } else importLedgerCsv(store, bytes)
                    }
                }
                result(outcome.getOrElse { "导入失败 · ${it.message ?: "请检查文件格式"}" })
            }
        }
    }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readSms(activity) } }
            script("syncLedger();")
        }
    }
    val bridge = remember(pageAttempt) {
        LedgerWebBridge(store, onWebDav = { operation, address, user, password ->
            activity.runOnUiThread {
                if (attempt == pageAttempt && loaded && !loadFailed) runWebDav(operation, address, user, password)
            }
        }) { action ->
            activity.runOnUiThread {
                if (attempt != pageAttempt) return@runOnUiThread
                when (action) {
                    "ready" -> if (!loadFailed) loaded = true
                    "startup-error" -> loadFailed = true
                    "notification" -> activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    "sms" -> sms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                    "export-json" -> exportBackup.launch("空叶记账-${LocalDate.now()}.json")
                    "export-csv" -> exportCsv.launch("空叶记账-${LocalDate.now()}.csv")
                    "import-json", "import-csv", "import-bills" -> {
                        importing = action.removePrefix("import-")
                        importFile.launch(arrayOf("*/*"))
                    }
                }
            }
        }
    }
    LaunchedEffect(pageAttempt, web) {
        val target = web ?: return@LaunchedEffect
        val html = withContext(Dispatchers.IO) {
            runCatching { activity.assets.open("ledger.html").bufferedReader().use { it.readText() } }
        }
        if (web == target && attempt == pageAttempt && !loadFailed) {
            html.onSuccess { target.loadDataWithBaseURL(LEDGER_PAGE, it, "text/html", "UTF-8", null) }
                .onFailure {
                    Log.w("LedgerWeb", "Unable to read the packaged ledger page")
                    loadFailed = true
                }
        }
    }
    LaunchedEffect(pageAttempt, loaded, loadFailed) {
        if (!loaded && !loadFailed) {
            delay(8_000)
            Log.w("LedgerWeb", "Ledger page did not report readiness")
            loadFailed = true
        }
    }
    LaunchedEffect(revision, loaded, loadFailed) { if (loaded && !loadFailed) script("syncLedger();") }
    LaunchedEffect(entry, loaded, loadFailed) {
        if (!loaded || loadFailed || entry == null) return@LaunchedEffect
        when {
            entry.getBooleanExtra("pending", false) -> script("showPending();")
            entry.getBooleanExtra("review", false) -> script("S.statsSub='worth';goTab('stats');")
            entry.getBooleanExtra("sources", false) -> script("goTab('auto');")
            entry.getBooleanExtra("monthly", false) -> script("goTab('stats');")
        }
        activity.entryIntent = null
    }
    BackHandler(loaded && !loadFailed) {
        web?.evaluateJavascript("navigateBack()") { if (it == "\"exit\"") activity.finish() }
    }
    val background = androidx.compose.ui.graphics.Color(0xfff7f3e8)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (!loadFailed) {
            key(pageAttempt) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        try {
                            WebView(context).apply {
                                setBackgroundColor(Color.rgb(247, 243, 232))
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                addJavascriptInterface(bridge, "Ledger")
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame && web == view) {
                                            Log.w("LedgerWeb", "Ledger page failed to load (${error.errorCode})")
                                            loadFailed = true
                                        }
                                    }

                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        if (web == view) {
                                            Log.w("LedgerWeb", "Ledger renderer exited")
                                            loadFailed = true
                                        }
                                        return true
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        if (!loaded && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                            Log.w("LedgerWeb", "Ledger JavaScript startup error at line ${message.lineNumber()}")
                                            loadFailed = true
                                        }
                                        return true
                                    }
                                }
                                web = this
                            }
                        } catch (_: RuntimeException) {
                            Log.w("LedgerWeb", "Could not initialize Android WebView")
                            loadFailed = true
                            View(context)
                        }
                    },
                    onRelease = { view ->
                        if (web == view) web = null
                        (view as? WebView)?.destroy()
                    }
                )
            }
        }
        if (loadFailed) {
            Column(
                Modifier.fillMaxSize().background(background).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("账本页面暂时无法打开")
                Button(onClick = { loaded = false; loadFailed = false; attempt++ }) { Text("重试") }
            }
        }
    }
}

private class LedgerWebBridge(
    private val store: Store,
    private val onWebDav: (String, String, String, String) -> Unit,
    private val launch: (String) -> Unit
) {
    @JavascriptInterface fun ready() = launch("ready")
    @JavascriptInterface fun startupFailed() = launch("startup-error")

    @JavascriptInterface fun configure(action: String) {
        if (action == "notification" || action == "sms") launch(action)
    }
    @JavascriptInterface fun importFile(format: String) {
        if (format in listOf("json", "csv", "bills")) launch("import-$format")
    }
    @JavascriptInterface fun exportFile(format: String) {
        if (format in listOf("json", "csv")) launch("export-$format")
    }
    @JavascriptInterface fun newId(): String = UUID.randomUUID().toString()

    @JavascriptInterface fun webDavConfig(): String {
        val preferences = store.ctx.getSharedPreferences("ledger_webdav", Context.MODE_PRIVATE)
        return JSONObject().put("address", preferences.getString("address", ""))
            .put("user", preferences.getString("user", "")).toString()
    }

    @JavascriptInterface fun webDav(operation: String, address: String, user: String, password: String) {
        if (operation == "test" || operation == "upload" || operation == "restore") {
            onWebDav(operation, address, user, password)
        }
    }

    @JavascriptInterface fun snapshot(): String = synchronized(store) {
        val today = LocalDate.now()
        val entries = store.txns()
        val rules = JSONObject()
        store.rows("Rule").forEach { rules.put(it.getString("word"), it.getString("cat")) }
        val stamps = JSONObject()
        store.prefs.asMap().forEach { (key, value) ->
            if (key.name.startsWith("mood:") && value in listOf("happy", "ok", "sad")) {
                stamps.put(key.name.removePrefix("mood:"), value)
            }
        }
        val quotas = JSONObject()
        (entries.map { day(it.time) }.toSet() + today).forEach { date ->
            quotas.put(date.toString(), store.quota(date, entries))
        }
        val switches = JSONObject()
        channels.forEach { (key, pkg) -> switches.put(key, store.notificationChannelEnabled(pkg)) }
        switches.put("sms", store.setting("sms", "true") == "true")
        JSONObject().put("txns", JSONArray(entries.map(::toWeb)))
            .put("firstMetDate", store.setting("firstMetDate", today.toString()))
            .put("budget", store.setting("budget", "300000").toLongOrNull() ?: 300000L)
            .put("quota", store.quota(today, entries))
            .put("quotas", quotas)
            .put("stamps", stamps)
            .put("rules", rules)
            .put("autoGuard", store.setting("autoGuard", "true") == "true")
            .put("listening", (PaymentListener.connected && store.setting("notifications", "true") == "true" &&
                channels.values.any(store::notificationChannelEnabled)) ||
                (switches.getBoolean("sms") && store.ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == 0))
            .put("channels", switches)
            .put("categories", JSONArray(store.categoryChoices().map { it.name }))
            .toString()
    }

    @JavascriptInterface fun apply(payload: String): String = synchronized(store) {
        runCatching {
            val patch = JSONObject(payload)
            val existing = store.txns().associateBy { it.id }
            val changed = patch.getJSONArray("changed").let { rows ->
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val id = row.getString("id")
                    fromWeb(row, existing[id])
                }
            }
            val deleted = patch.getJSONArray("deleted")
            val settings = patch.getJSONObject("settings")
            store.db.runInTransaction {
                changed.forEach { item ->
                    if (item.direction == "退款" && item.refundOf.isBlank()) store.linkRefund(item)
                    store.put("Txn", item.id, item.json())
                }
                for (index in 0 until deleted.length()) {
                    val id = deleted.getString(index)
                    if (id in existing) store.delete(id)
                }
                settings.optJSONObject("rules")?.let { rules ->
                    val known = store.rows("Rule").associate { it.getString("word") to it.getString("cat") }
                    rules.keys().forEach { word ->
                        val category = rules.getString(word)
                        if (known[word] != category && word.isNotBlank()) store.rule(word, category, true)
                    }
                }
            }
            if (settings.has("firstMetDate")) {
                val date = LocalDate.parse(settings.getString("firstMetDate"))
                require(date <= LocalDate.now())
                store.set("firstMetDate", date.toString())
            }
            if (settings.has("budget")) {
                store.set("budget", settings.getLong("budget").also { require(it > 0) }.toString())
            }
            if (settings.has("autoGuard")) store.set("autoGuard", settings.getBoolean("autoGuard").toString())
            settings.optJSONObject("channels")?.let { switches ->
                channels.forEach { (key, pkg) ->
                    val enabled = switches.getBoolean(key)
                    if (enabled != store.notificationChannelEnabled(pkg)) store.set("channel:$pkg", enabled.toString())
                }
                val sms = switches.getBoolean("sms").toString()
                if (sms != store.setting("sms", "true")) store.set("sms", sms)
            }
            settings.optJSONObject("stamps")?.let { stamps ->
                stamps.keys().forEach { key ->
                    val date = LocalDate.parse(key)
                    val mood = stamps.getString(key)
                    require(date <= LocalDate.now() && mood in listOf("happy", "ok", "sad"))
                    if (store.setting("mood:$date") != mood) store.set("mood:$date", mood)
                }
            }
            ""
        }.getOrElse { "保存失败，请检查输入后重试" }
    }

    @JavascriptInterface fun parseMessage(message: String): String = synchronized(store) {
        runCatching {
            require(message.isNotBlank() && message.length <= 2000) { "请粘贴一条付款通知或银行动账短信" }
            val parser = LedgerParser(store.parserText())
            val id = UUID.nameUUIDFromBytes(message.trim().toByteArray(Charsets.UTF_8)).toString()
            if (store.db.records().find(id) != null) return@runCatching JSONObject().put("id", id).toString()
            val raw = JSONObject().put("id", id).put("body", message).put("time", System.currentTimeMillis())
            val parsed = listOf("sms", "notification").firstNotNullOfOrNull { source ->
                parser.parse(raw.put("source", source)).transaction
            } ?: error("未识别到明确的交易金额")
            val merchant = Regex("(?:商户|收款方|摘要)[：:]\\s*([^，。\\n]+)").find(message)
            parsed.merchant = merchant?.groupValues?.get(1)?.trim() ?: parsed.merchant
            parsed.category = if (parsed.direction == "收入") {
                when {
                    "工资" in message -> "工资"
                    "兼职" in message -> "兼职"
                    "理财" in message -> "理财"
                    else -> "收入"
                }
            } else store.classify(parsed.merchant)
            parsed.source = "粘贴识别"
            parsed.note = ""
            parsed.status = "待确认"
            store.save(parsed.copy(id = id, events = mutableListOf()))
            JSONObject().put("id", id).toString()
        }.getOrElse { JSONObject().put("error", it.message ?: "识别失败").toString() }
    }
}

private fun toWeb(item: Txn): JSONObject {
    val date = Instant.ofEpochMilli(item.time).atZone(ZoneId.systemDefault())
    return JSONObject().put("id", item.id).put("date", date.toLocalDate().toString())
        .put("time", date.format(timeFormat)).put("cents", item.cents).put("merchant", item.merchant)
        .put("category", item.category).put("dir", item.direction).put("source", item.source)
        .put("note", item.note).put("score", item.score).put("status", item.status)
        .put("refundOf", item.refundOf)
        .put("auto", item.events.isNotEmpty()).put("merged", item.events.size > 1)
}

private fun fromWeb(row: JSONObject, previous: Txn?): Txn {
    val amount = row.getLong("cents")
    val direction = row.getString("dir")
    val status = row.getString("status")
    require(amount > 0 && direction in listOf("支出", "收入", "退款", "转账") &&
        status in listOf("待确认", "已入账", "已忽略"))
    val date = row.getString("date")
    val time = row.getString("time")
    val timestamp = if (previous != null && toWeb(previous).let { it.getString("date") == date && it.getString("time") == time }) previous.time
        else LocalDateTime.parse("${date}T${time}").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return (previous ?: Txn(id = row.getString("id"), cents = amount, merchant = "", category = "其他")).copy(
        cents = amount, merchant = row.getString("merchant").take(300), category = row.getString("category").take(80),
        direction = direction, time = timestamp, source = previous?.source ?: "手动", status = status,
        note = row.optString("note").take(2000), score = row.optInt("score", -1)
    )
}

private val ledgerCsvHeaders = listOf("日期", "时间", "方向", "种类", "商户", "金额(元)", "渠道", "备注")
private fun ledgerCsv(entries: List<Txn>): String {
    val rows = entries.map { item ->
        val row = toWeb(item)
        listOf(row.getString("date"), row.getString("time"), item.direction, item.category, item.merchant,
            money(item.cents), item.source, item.note)
    }
    return "\uFEFF" + (listOf(ledgerCsvHeaders) + rows).joinToString("\r\n") { cells ->
        cells.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }
    }
}

private fun importLedgerCsv(store: Store, bytes: ByteArray): String {
    val rows = BillImport.csv(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
    if (rows.firstOrNull() != ledgerCsvHeaders) return BillImport.run(store, bytes)
    val entries = rows.drop(1).filter { it.any(String::isNotBlank) }.map { row ->
        require(row.size == ledgerCsvHeaders.size) { "CSV 列数不正确" }
        val id = UUID.nameUUIDFromBytes(JSONArray(row).toString().toByteArray()).toString()
        fromWeb(JSONObject().put("id", id).put("date", row[0]).put("time", row[1])
            .put("dir", row[2]).put("category", row[3]).put("merchant", row[4]).put("cents", cents(row[5]))
            .put("status", "已入账").put("note", row[7]), null).copy(source = row[6])
    }
    var count = 0
    synchronized(store) {
        store.db.runInTransaction {
            entries.forEach { item ->
                if (store.db.records().find(item.id) == null) {
                    store.put("Txn", item.id, item.json())
                    count++
                }
            }
        }
    }
    return "已导入 $count 笔账单"
}
