package com.kongye.ledger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

internal fun screenRecognitionStatus(context: Context): String {
    if (Build.VERSION.SDK_INT < 30) return "unsupported"
    val component = ComponentName(context, PaymentScreenService::class.java)
    val enabled = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }.getOrDefault(false) || context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { ComponentName.unflattenFromString(it.id) == component }
    if (!enabled) return "permission"
    if (Store.get(context).setting("screenRecognition", "false") != "true") return "off"
    return if (PaymentScreenService.connected) "active" else "disconnected"
}

class PaymentScreenService : AccessibilityService() {
    companion object {
        @Volatile internal var connected = false
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizerDelegate = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val recognizer by recognizerDelegate
    private var reading = false
    private var lastCapture = 0L
    private var stopped = false
    private var scanScheduled = false
    // Some payment WebViews omit accessibility events when restored from the background.
    // Keep checking the foreground package; readPayment never captures other apps or a locked screen.
    private val scan = Runnable {
        scanScheduled = false
        try { readPayment() } finally { scheduleScan(5000) }
    }
    private fun scheduleScan(delay: Long) {
        if (stopped || scanScheduled) return
        scanScheduled = true
        handler.postDelayed(scan, delay)
    }

    override fun onServiceConnected() {
        connected = true
        Store.get(this).changed()
        scheduleScan(950)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in paymentNotificationChannels.values) return
        android.util.Log.d("LedgerScreen", "Supported app accessibility event")
        scheduleScan(950)
    }

    private fun permittedPackage(): String? {
        if (stopped || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val store = Store.get(this)
        if (store.setting("screenRecognition", "false") != "true" || store.setting("autoGuard", "true") != "true") return null
        val pkg = rootInActiveWindow?.packageName?.toString() ?: run {
            android.util.Log.d("LedgerScreen", "Active window not available")
            return null
        }
        return pkg.takeIf { it in paymentNotificationChannels.values && store.notificationChannelEnabled(it) }
    }

    private fun readPayment() {
        if (Build.VERSION.SDK_INT < 30 || reading) return
        val pkg = permittedPackage() ?: return
        val since = android.os.SystemClock.elapsedRealtime() - lastCapture
        if (since < 2500) {
            scheduleScan(2500 - since)
            return
        }
        reading = true
        android.util.Log.d("LedgerScreen", "Reading supported payment app")
        lastCapture = android.os.SystemClock.elapsedRealtime()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                } catch (_: RuntimeException) {
                    null
                } finally { buffer.close() }
                if (bitmap == null || permittedPackage() != pkg) {
                    bitmap?.recycle()
                    reading = false
                    return
                }
                val task = try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                } catch (_: RuntimeException) {
                    bitmap.recycle()
                    reading = false
                    Store.get(this@PaymentScreenService).set("screenRecognitionError", "文字识别初始化失败，请重新打开空叶记账后再试。")
                    return
                }
                task
                    .addOnSuccessListener { text ->
                        if (permittedPackage() == pkg) {
                            if (Store.get(this@PaymentScreenService).setting("screenRecognitionError").isNotBlank()) {
                                Store.get(this@PaymentScreenService).set("screenRecognitionError", "")
                            }
                            val now = System.currentTimeMillis()
                            val lines = text.textBlocks.flatMap { it.lines }
                                .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                                .joinToString("\n") { it.text }
                            val payment = ScreenPaymentParser.parse(pkg, lines, now)
                            android.util.Log.d("LedgerScreen", if (payment == null) "No unambiguous payment on screen" else "Payment recognized")
                            if (payment != null) worker.execute {
                                val store = Store.get(this@PaymentScreenService)
                                if (store.setting("screenRecognition", "false") == "true" && store.setting("autoGuard", "true") == "true") {
                                    val key = listOf(pkg, payment.merchant, payment.cents, payment.time / 60_000).joinToString("|")
                                    runCatching { store.ingest("screen", pkg, "屏幕识别", payment.json().toString(), payment.time, key) }
                                        .onFailure { android.util.Log.w("LedgerCapture", "Screen payment could not be saved") }
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        if (!stopped) Store.get(this@PaymentScreenService).set("screenRecognitionError", "屏幕文字识别失败，请重新打开付款结果页。")
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        reading = false
                    }
            }

            override fun onFailure(errorCode: Int) {
                reading = false
                if (!stopped && permittedPackage() == pkg) {
                    Store.get(this@PaymentScreenService).set("screenRecognitionError", "系统未允许读取此页面，请检查无障碍权限或付款页面保护。")
                }
            }
        })
    }

    override fun onInterrupt() {
        handler.removeCallbacks(scan)
        scanScheduled = false
    }

    override fun onDestroy() {
        stopped = true
        connected = false
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        if (recognizerDelegate.isInitialized()) recognizer.close()
        Store.get(this).changed()
        super.onDestroy()
    }
}
