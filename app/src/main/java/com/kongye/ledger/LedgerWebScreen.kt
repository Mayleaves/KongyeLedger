package com.kongye.ledger

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import android.webkit.WebSettings
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume

private const val LEDGER_PAGE = "https://appassets.androidplatform.net/assets/ledger.html"
private val ledgerSystemInsets = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private data class WebDavResult(val success: Boolean, val message: String, val settingsSaved: Boolean = false)

internal fun Store.notificationChannelEnabled(packageName: String): Boolean =
    setting("channel:$packageName", "true") == "true"

internal suspend fun prepareLedgerWebView(context: Context) {
    // The standard constructor is still available if prewarming fails or takes too long.
    try {
        val prepared = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { continuation ->
                val config = WebViewStartUpConfig.Builder(Dispatchers.IO.asExecutor()).build()
                WebViewCompat.startUpWebView(context, config, object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = continuation.resume(true)

                    override fun onError(error: WebViewStartupException) {
                        Log.w("LedgerWeb", "Unable to prewarm the ledger WebView", error)
                        continuation.resume(false)
                    }
                })
            }
        }
        if (prepared == null) Log.w("LedgerWeb", "Prewarming the ledger WebView timed out")
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        Log.w("LedgerWeb", "Unable to start prewarming the ledger WebView", error)
    }
}

@Composable
internal fun LedgerWebScreen(
    activity: MainActivity,
    store: Store,
    onStartupProgress: (Float) -> Unit,
    onPresented: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val pageAttempt = attempt
    val revision by store.revision.collectAsState()
    val listenerConnected by PaymentListener.connection.collectAsState()
    val entry = activity.entryIntent
    val reportProgress by rememberUpdatedState(onStartupProgress)
    val presentLedger by rememberUpdatedState(onPresented)
    var davInProgress by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    val davSettings = remember(activity) { LedgerDavSettings(activity.applicationContext) }
    val installedVersion = remember(activity) {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    }
    var updateDownload by remember { mutableStateOf(JSONObject().put("status", "idle")) }
    var downloadedUpdate by remember { mutableStateOf<java.io.File?>(null) }
    var updateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var updateGeneration by remember { mutableIntStateOf(0) }
    val apkDownload = remember(activity) { LedgerApkDownload(activity.applicationContext) }
    DisposableEffect(apkDownload) { onDispose { apkDownload.cancel() } }

    fun script(code: String) { web?.evaluateJavascript(code, null) }
    fun result(message: String) = script("syncLedger(); toast(${JSONObject.quote(message)});")
    fun updateDownloadState(state: JSONObject) {
        updateDownload = state
        script("setUpdateDownloadState(${JSONObject.quote(state.toString())});")
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = downloadedUpdate
        if (activity.packageManager.canRequestPackageInstalls() && file != null) {
            runCatching { activity.startActivity(ledgerUpdateInstallIntent(activity, file)) }
                .onFailure { result("无法打开安装器，请点击安装重试。") }
        } else {
            result("未获得安装权限，安装包已保存在本机，可点击安装重试。")
        }
    }
    fun installDownloadedUpdate() {
        val file = downloadedUpdate ?: run { result("请先下载安装包。"); return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { verifyLedgerUpdateApk(activity, file) }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    result("请允许空叶记账安装应用，返回后继续安装。")
                    installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", activity.packageName, null)))
                } else activity.startActivity(ledgerUpdateInstallIntent(activity, file))
            } catch (error: CancellationException) { throw error }
            catch (error: LedgerUpdateException) { result(error.message ?: "安装包校验失败，请重新下载。") }
            catch (_: Exception) { result("无法打开安装器，请稍后重试。") }
        }
    }
    fun downloadUpdate(tag: String) {
        if (updateJob != null) return
        if (LedgerUpdates.githubUrl("apk", tag) == null) { result("版本号无效。"); return }
        val generation = ++updateGeneration
        downloadedUpdate = null
        updateDownloadState(JSONObject().put("status", "connecting").put("tag", tag))
        updateJob = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val asset = LedgerUpdates().apk(tag)
                    val apk = apkDownload.download(asset) { received, total ->
                        activity.runOnUiThread {
                            if (generation == updateGeneration && updateJob?.isCancelled != true) updateDownloadState(JSONObject()
                                .put("status", "downloading").put("tag", tag).put("received", received).put("total", total))
                        }
                    }
                    activity.runOnUiThread {
                        if (generation == updateGeneration && updateJob?.isCancelled != true) updateDownloadState(JSONObject().put("status", "verifying").put("tag", tag))
                    }
                    verifyLedgerUpdateApk(activity, apk)
                    apk
                }
                if (generation == updateGeneration) {
                    downloadedUpdate = file
                    updateDownloadState(JSONObject().put("status", "ready").put("tag", tag))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == updateGeneration) updateDownloadState(JSONObject().put("status", "error").put("tag", tag)
                    .put("message", if (error is LedgerUpdateException || error is IllegalArgumentException) error.message else "下载失败，请检查网络后重试"))
            } finally {
                if (generation == updateGeneration) {
                    if (updateJob?.isCancelled == true) updateDownloadState(JSONObject().put("status", "idle"))
                    updateJob = null
                }
            }
        }
    }
    fun checkUpdates() {
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                try {
                    LedgerUpdates().check(installedVersion)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LedgerUpdateException) {
                    JSONObject().put("error", error.message)
                } catch (_: Exception) {
                    JSONObject().put("error", "暂时无法连接 GitHub，请检查网络后重试")
                }
            }
            if (attempt == pageAttempt && loaded && !loadFailed) {
                script("finishUpdateCheck(${JSONObject.quote(payload.toString())});")
            }
        }
    }
    fun runWebDav(operation: String, address: String, user: String, password: String, reusePassword: Boolean) {
        if (davInProgress) return
        davInProgress = true
        scope.launch {
            try {
                val (success, message, settingsSaved) = withContext(Dispatchers.IO) {
                    val account = try {
                        davSettings.account(address, user, password, reusePassword)
                    } catch (e: IllegalArgumentException) {
                        return@withContext WebDavResult(false, e.message ?: "请检查 WebDAV 设置")
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
                            else -> return@withContext WebDavResult(false, "不支持此操作")
                        }
                        val saved = reusePassword || davSettings.save(account)
                        WebDavResult(true, if (saved) message else "$message，但未能保存 WebDAV 设置", saved)
                    } catch (e: LedgerDavException) {
                        WebDavResult(false, e.message ?: "WebDAV 操作失败")
                    } catch (e: IllegalArgumentException) {
                        WebDavResult(false, e.message ?: "请检查备份文件")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: javax.net.ssl.SSLException) {
                        WebDavResult(false, "无法验证服务器的 HTTPS 连接")
                    } catch (_: java.net.SocketTimeoutException) {
                        WebDavResult(false, "连接超时，请检查网络和服务器")
                    } catch (_: Exception) {
                        WebDavResult(false, "WebDAV 操作失败，请检查地址、网络和目录权限")
                    }
                }
                script("finishWebDav($success,${JSONObject.quote(message)},$settingsSaved);")
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
            result(when (outcome.exceptionOrNull()) {
                null -> "已导出文件"
                is SecurityException -> "没有文件写入权限，请重新选择保存位置。"
                else -> "导出失败，请重试"
            })
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
                result(outcome.getOrElse {
                    if (it is SecurityException) "没有文件读取权限，请重新选择文件。"
                    else "导入失败 · ${it.message ?: "请检查文件格式"}"
                })
            }
        }
    }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val status = smsListeningStatus(activity)
        if (status != "active") {
            result("短信授权未完成，可重新授权或在系统设置中检查短信权限。")
        }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readSms(activity) } }
            script("syncLedger();")
        }
    }
    val reminders = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        result(if (granted) "记账提醒权限已授予。" else "未获得记账提醒权限，可在系统通知设置中开启。")
    }
    fun openSettings(intent: Intent, failure: String) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            result(failure)
        } catch (_: SecurityException) {
            result(failure)
        }
    }
    fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                paymentListenerComponent(activity).flattenToString()
            )
            if (runCatching { activity.startActivity(detail) }.isSuccess) return
        }
        openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "无法打开通知授权，请在系统设置中开启空叶记账的通知读取权。")
    }
    val permissionRequests = activity.getSharedPreferences("ledger_permissions", Context.MODE_PRIVATE)
    fun requestSmsAccess() {
        if (smsListeningStatus(activity) == "active") {
            result("短信权限已授予。")
            return
        }
        val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (permissionRequests.getBoolean("sms_requested", false) && permissions.none(activity::shouldShowRequestPermissionRationale)) {
            openSettings(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                "无法打开授权页面，请在空叶记账的应用权限中开启短信权限。"
            )
        } else {
            permissionRequests.edit().putBoolean("sms_requested", true).apply()
            sms.launch(permissions)
        }
    }
    fun requestReminderAccess() {
        if (store.setting("reminders", "true") != "true") store.set("reminders", "true")
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!permissionRequests.getBoolean("reminders_requested", false) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            permissionRequests.edit().putBoolean("reminders_requested", true).apply()
            reminders.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = if (reminderPermissionStatus(activity) == "channel-blocked") {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, REMINDER_CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        openSettings(intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName), "无法打开系统通知设置。")
    }
    fun retryNotificationListener() {
        if (reconnecting) return
        if (store.setting("notifications", "true") != "true") store.set("notifications", "true")
        if (!notificationAccess(activity)) {
            openNotificationSettings()
            return
        }
        reconnecting = true
        script("setListenerReconnect(true);")
        scope.launch {
            val message = try {
                if (PaymentListener.reconnect(activity.applicationContext)) "通知监听已连接。"
                else "系统尚未连接通知监听，请打开授权页，关闭再开启通知读取权。"
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                "系统无法重启通知监听，请打开授权页检查通知读取权。"
            } finally {
                reconnecting = false
                script("setListenerReconnect(false);")
            }
            result(message)
        }
    }
    fun checkTodaysNotifications() {
        if (store.setting("autoGuard", "true") != "true") {
            result("请先开启自动记账。")
            return
        }
        if (!PaymentListener.connected || store.setting("notifications", "true") != "true") {
            retryNotificationListener()
            return
        }
        scope.launch {
            val message = try {
                val found = PaymentListener.checkTodaysNotifications(activity)
                when {
                    found == null -> "通知监听未连接，请重新连接后再试。"
                    found == 0 -> "通知栏里没有可补记的今日付款通知。"
                    else -> "已处理 $found 条付款通知，可在流水中查看或核对。"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                "读取通知栏失败，请检查通知读取权。"
            }
            result(message)
        }
    }
    val bridge = remember(pageAttempt) {
        LedgerWebBridge(store, davSettings, installedVersion, onGitHub = { url ->
            activity.runOnUiThread {
                if (attempt == pageAttempt && loaded && !loadFailed) {
                    openSettings(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "无法打开 GitHub，请确认手机已安装浏览器。")
                }
            }
        }, onUpdate = { action, tag ->
            activity.runOnUiThread {
                if (attempt == pageAttempt && loaded && !loadFailed) when (action) {
                    "download" -> downloadUpdate(tag)
                    "install" -> installDownloadedUpdate()
                    "cancel" -> {
                        apkDownload.cancel()
                        updateJob?.cancel()
                        updateDownloadState(JSONObject().put("status", if (updateJob == null) "idle" else "cancelling")
                            .put("tag", updateDownload.optString("tag")))
                    }
                }
            }
        }, updateStatus = { updateDownload.toString() }, onWebDav = { operation, address, user, password, reusePassword ->
            activity.runOnUiThread {
                if (attempt == pageAttempt && loaded && !loadFailed) runWebDav(operation, address, user, password, reusePassword)
            }
        }) { action ->
            activity.runOnUiThread {
                if (attempt != pageAttempt) return@runOnUiThread
                when (action) {
                    "ready" -> if (!loaded && !loadFailed) {
                        val target = web ?: return@runOnUiThread
                        reportProgress(.94f)
                        target.postVisualStateCallback(pageAttempt.toLong(), object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (attempt == pageAttempt && web == target && !loadFailed) loaded = true
                            }
                        })
                    }
                    "startup-error" -> loadFailed = true
                    "check-updates" -> if (loaded && !loadFailed) checkUpdates()
                    "notification" -> openNotificationSettings()
                    "notification-retry" -> retryNotificationListener()
                    "notification-check-today" -> checkTodaysNotifications()
                    "screen-recognition" -> {
                        if (Build.VERSION.SDK_INT < 30) {
                            result("屏幕识别需要 Android 11 或以上版本。")
                        } else {
                            script("openScreenRecognitionSheet();")
                        }
                    }
                    "screen-enable" -> {
                        if (Build.VERSION.SDK_INT < 30) {
                            result("屏幕识别需要 Android 11 或以上版本。")
                        } else {
                            store.set("screenRecognition", "true")
                            store.set("screenRecognitionError", "")
                            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开无障碍设置，请在系统设置搜索「无障碍」。")
                        }
                    }
                    "screen-pause" -> {
                        store.set("screenRecognition", "false")
                        result("屏幕识别已暂停。")
                    }
                    "sms" -> requestSmsAccess()
                    "reminders" -> requestReminderAccess()
                    "battery" -> openSettings(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        "无法打开电池设置，请在系统设置中检查空叶记账的后台限制。"
                    )
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
        reportProgress(.25f)
        val html = withContext(Dispatchers.IO) {
            runCatching { activity.assets.open("ledger.html").bufferedReader().use { it.readText() } }
        }
        if (web == target && attempt == pageAttempt && !loadFailed) {
            html.onSuccess {
                reportProgress(.30f)
                target.loadDataWithBaseURL(LEDGER_PAGE, it, "text/html", "UTF-8", null)
            }
                .onFailure {
                    Log.w("LedgerWeb", "Unable to read the packaged ledger page")
                    loadFailed = true
                }
        }
    }
    LaunchedEffect(pageAttempt, loaded, loadFailed) {
        if (loaded || loadFailed) {
            presentLedger(loaded && !loadFailed)
        } else {
            delay(8_000)
            Log.w("LedgerWeb", "Ledger page did not report readiness")
            loadFailed = true
        }
    }
    LaunchedEffect(revision, listenerConnected, loaded, loadFailed) { if (loaded && !loadFailed) script("syncLedger();") }
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
    val background = colorResource(R.color.ledger_launch_background)
    val webViewHandlesIme = remember(activity) {
        WebViewCompat.getCurrentWebViewPackage(activity)?.versionName
            ?.substringBefore('.')?.toIntOrNull()?.let { it >= 139 } == true
    }
    val contentInsets = if (webViewHandlesIme) WindowInsets.systemBars.union(WindowInsets.displayCutout)
        else WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) {
        if (!loadFailed) {
            key(pageAttempt) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        try {
                            WebView(context).apply {
                                setBackgroundColor(context.getColor(R.color.ledger_launch_background))
                                settings.javaScriptEnabled = true
                                settings.useWideViewPort = true
                                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                                    // Let recent WebViews resize their visual viewport without resizing the native view.
                                    val fittedTypes = if (webViewHandlesIme) ledgerSystemInsets
                                        else ledgerSystemInsets or WindowInsetsCompat.Type.ime()
                                    val fittedInsets = WindowInsetsCompat.Builder(insets)
                                        .setInsets(fittedTypes, Insets.NONE)
                                        .build()
                                    ViewCompat.onApplyWindowInsets(view, fittedInsets)
                                }
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
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        if (view == web && attempt == pageAttempt && !loaded && !loadFailed) {
                                            reportProgress(.30f + newProgress.coerceIn(0, 100) * .005f)
                                        }
                                    }

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
            LedgerLoadError(background) { loaded = false; loadFailed = false; attempt++ }
        }
    }
}

@Composable
internal fun LedgerLoadError(background: Color, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("账本页面暂时无法打开")
        Button(onClick = onRetry) { Text("重试") }
    }
}

private class LedgerWebBridge(
    private val store: Store,
    private val davSettings: LedgerDavSettings,
    private val installedVersion: String,
    private val onGitHub: (String) -> Unit,
    private val onUpdate: (String, String) -> Unit,
    private val updateStatus: () -> String,
    private val onWebDav: (String, String, String, String, Boolean) -> Unit,
    private val launch: (String) -> Unit
) {
    @JavascriptInterface fun ready() = launch("ready")
    @JavascriptInterface fun startupFailed() = launch("startup-error")

    @JavascriptInterface fun configure(action: String) {
        if (action in listOf("notification", "notification-retry", "notification-check-today", "screen-recognition", "screen-enable", "screen-pause", "sms", "reminders", "battery")) launch(action)
    }
    @JavascriptInterface fun importFile(format: String) {
        if (format in listOf("json", "csv", "bills")) launch("import-$format")
    }
    @JavascriptInterface fun exportFile(format: String) {
        if (format in listOf("json", "csv")) launch("export-$format")
    }
    @JavascriptInterface fun newId(): String = UUID.randomUUID().toString()
    @JavascriptInterface fun appVersion(): String = installedVersion
    @JavascriptInterface fun downloadUpdate(tag: String) { if (LedgerUpdates.githubUrl("apk", tag) != null) onUpdate("download", tag) }
    @JavascriptInterface fun installUpdate() = onUpdate("install", "")
    @JavascriptInterface fun cancelUpdate() = onUpdate("cancel", "")
    @JavascriptInterface fun updateDownloadStatus(): String = updateStatus()
    @JavascriptInterface fun checkUpdates() = launch("check-updates")
    @JavascriptInterface fun addManualCategory(direction: String, name: String): String = runCatching {
        JSONObject().put("name", store.addManualCategory(direction, name))
    }.getOrElse { error ->
        JSONObject().put("error", if (error is IllegalArgumentException) error.message else "分类保存失败，请重试")
    }.toString()

    @JavascriptInterface fun changeManualCategory(direction: String, name: String, action: String): String = runCatching {
        store.changeManualCategory(direction, name, action)
        ""
    }.getOrElse { error ->
        if (error is IllegalArgumentException) error.message.orEmpty() else "分类保存失败，请重试"
    }

    @JavascriptInterface fun calculateEntryAmount(expression: String): String = runCatching {
        JSONObject().put("cents", entryAmount(expression))
    }.getOrElse { error ->
        JSONObject().put("error", if (error is IllegalArgumentException) error.message else "无法计算这条算式")
    }.toString()

    @JavascriptInterface fun openGitHub(page: String, tag: String) {
        if (page == "apk") downloadUpdate(tag) else LedgerUpdates.githubUrl(page, tag)?.let(onGitHub)
    }

    @JavascriptInterface fun webDavConfig(): String = davSettings.publicConfig().toString()

    @JavascriptInterface fun webDav(operation: String, address: String, user: String, password: String, reusePassword: Boolean) {
        if (operation == "test" || operation == "upload" || operation == "restore") {
            onWebDav(operation, address, user, password, reusePassword)
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
        val todayBasis = store.calculateBudgetBasis(today, entries)
        val todayQuota = todayBasis?.daily ?: 0L
        val quotas = JSONObject().put(today.toString(), todayQuota)
        entries.map { day(it.time) }.toSet().forEach { date ->
            if (date != today) quotas.put(date.toString(), store.quota(date, entries))
        }
        val switches = JSONObject()
        paymentNotificationChannels.forEach { (key, pkg) -> switches.put(key, store.notificationChannelEnabled(pkg)) }
        switches.put("sms", store.setting("sms", "true") == "true")
        val autoGuard = store.setting("autoGuard", "true") == "true"
        val hasNotificationAccess = notificationAccess(store.ctx)
        val smsStatus = smsListeningStatus(store.ctx)
        val notificationStatus = when {
            store.setting("notifications", "true") != "true" -> "disabled"
            !hasNotificationAccess -> "permission"
            !PaymentListener.connected -> "disconnected"
            else -> "active"
        }
        val statuses = JSONObject()
        switches.keys().forEach { channel ->
            val status = when {
                !switches.getBoolean(channel) -> "off"
                !autoGuard -> "paused"
                channel == "sms" -> smsStatus
                else -> notificationStatus
            }
            statuses.put(channel, status)
        }
        val expenseCategories = store.manualCategories("支出")
        val incomeCategories = store.manualCategories("收入")
        JSONObject().put("txns", JSONArray(entries.map(::toWeb)))
            .put("firstMetDate", store.setting("firstMetDate", today.toString()))
            .put("budgetLimits", JSONObject(store.budgetLimits().mapKeys { it.key.id }))
            .put("budgetSpent", JSONObject(store.budgetSpent(today, entries).mapKeys { it.key.id }))
            .put("quota", todayQuota)
            .put("quotaDetails", todayBasis?.json() ?: JSONObject.NULL)
            .put("quotas", quotas)
            .put("stamps", stamps)
            .put("rules", rules)
            .put("autoGuard", autoGuard)
            .put("listening", statuses.keys().asSequence().any { statuses.getString(it) == "active" })
            .put("channelStatuses", statuses)
            .put("screenRecognition", screenRecognitionStatus(store.ctx))
            .put("screenRecognitionError", store.setting("screenRecognitionError"))
            .put("permissions", JSONObject()
                .put("notification", hasNotificationAccess)
                .put("sms", smsStatus)
                .put("reminders", if (store.setting("reminders", "true") == "true") reminderPermissionStatus(store.ctx) else "off")
                .put("battery", store.ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(store.ctx.packageName)))
            .put("channels", switches)
            .put("categories", JSONArray((expenseCategories + incomeCategories).distinct()))
            .put("manualCategories", JSONObject().put("支出", JSONArray(expenseCategories)).put("收入", JSONArray(incomeCategories)))
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
            settings.optJSONObject("budgetLimits")?.let { limits ->
                store.setBudgetLimits(BudgetPeriod.entries.associateWith { limits.getLong(it.id) })
            }
            if (settings.has("autoGuard")) store.set("autoGuard", settings.getBoolean("autoGuard").toString())
            settings.optJSONObject("channels")?.let { switches ->
                paymentNotificationChannels.forEach { (key, pkg) ->
                    val enabled = switches.optBoolean(key, store.notificationChannelEnabled(pkg))
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

internal fun toWeb(item: Txn): JSONObject {
    val date = Instant.ofEpochMilli(item.time).atZone(ZoneId.systemDefault())
    return JSONObject().put("id", item.id).put("date", date.toLocalDate().toString())
        .put("time", date.format(timeFormat)).put("cents", item.cents).put("merchant", item.merchant)
        .put("category", item.category).put("dir", item.direction).put("source", item.source)
        .put("note", item.note).put("score", item.score).put("status", item.status)
        .put("refundOf", item.refundOf)
        .put("auto", item.events.isNotEmpty()).put("merged", item.events.size > 1)
}

internal fun fromWeb(row: JSONObject, previous: Txn?): Txn {
    val amount = row.getLong("cents")
    val direction = row.getString("dir")
    val status = row.getString("status")
    require(amount > 0 && direction in listOf("支出", "收入", "退款", "转账") &&
        status in listOf("待确认", "已入账", "已忽略"))
    val dateTime = LocalDateTime.parse(row.getString("date") + "T" + row.getString("time"))
    val zone = ZoneId.systemDefault()
    val previousTime = previous?.let { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDateTime() }
    val timestamp = if (previous != null && previousTime?.withNano(0) == dateTime) previous.time
        else dateTime.atZone(zone).toInstant().toEpochMilli()
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
