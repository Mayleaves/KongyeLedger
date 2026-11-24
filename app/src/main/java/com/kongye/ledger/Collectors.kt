package com.kongye.ledger
import android.app.*
import android.content.*
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.*
import android.content.pm.PackageManager
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.time.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val REMINDER_CHANNEL_ID = "ledger"

internal fun reminderPermissionStatus(context: Context): String {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return "permission"
    return if (manager.getNotificationChannel(REMINDER_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
        "channel-blocked"
    } else {
        "active"
    }
}

fun notifyUser(c:Context,title:String,text:String,id:Int){
 if(android.os.Build.VERSION.SDK_INT>=33&&c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)return
 val s=Store.get(c);if(s.setting("reminders","true")!="true")return
 val nm=c.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL_ID,"记账提醒",NotificationManager.IMPORTANCE_DEFAULT))
 val pi=PendingIntent.getActivity(c,id,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("pending",id==11).putExtra("review",id==15).putExtra("sources",id==12).putExtra("monthly",id==17),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 nm.notify(id,Notification.Builder(c,REMINDER_CHANNEL_ID).setSmallIcon(com.kongye.ledger.R.drawable.ic_launcher).setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build())
}
internal fun paymentListenerComponent(context: Context) = ComponentName(context, PaymentListener::class.java)

fun notificationAccess(c: Context): Boolean {
 val component = paymentListenerComponent(c)
 return if (Build.VERSION.SDK_INT >= 27) {
  c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
 } else {
  Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
   .split(':').any { ComponentName.unflattenFromString(it) == component }
 }
}

internal fun smsListeningStatus(context: Context): String {
    val read = context.checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val receive = context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    return when {
        read && receive -> "active"
        read || receive -> "partial"
        else -> "permission"
    }
}

class PaymentListener:NotificationListenerService(){
 companion object {
  private val listenerConnection = MutableStateFlow(false)
  internal val connection = listenerConnection.asStateFlow()
  val connected: Boolean get() = connection.value
  private var boundListener: PaymentListener? = null
  private val repairing = AtomicBoolean(false)

  internal fun requestConnection(context: Context) {
   if (connected || repairing.get() || !notificationAccess(context)) return
   runCatching { requestRebind(paymentListenerComponent(context)) }
    .onFailure { android.util.Log.w("LedgerCapture", "Unable to request notification listener connection") }
  }

  internal suspend fun reconnect(context: Context): Boolean {
   if (!notificationAccess(context)) return false
   if (connected) return true
   if (!repairing.compareAndSet(false, true)) return awaitConnection(context)
   try {
    val component = paymentListenerComponent(context)
    if (Build.VERSION.SDK_INT >= 34) {
     try {
      requestUnbind(component)
      delay(300)
     } finally {
      if (notificationAccess(context)) requestRebind(component)
     }
    } else {
     requestRebind(component)
    }
    return awaitConnection(context)
   } finally {
    repairing.set(false)
   }
  }

  private suspend fun awaitConnection(context: Context): Boolean =
   withTimeoutOrNull(5_000) { connection.first { it } } == true && notificationAccess(context)

  internal suspend fun checkTodaysNotifications(context: Context): Int? {
   val listener = boundListener?.takeIf { notificationAccess(context) } ?: return null
   val since = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
   val notifications = listener.activeNotifications.orEmpty().filter { it.postTime >= since }
   return suspendCancellableCoroutine { continuation ->
    captureExecutor.execute {
     val count = notifications.sortedBy { it.postTime }.count { sbn ->
      runCatching { capturePaymentNotification(Store.get(listener), sbn) }
       .onFailure { android.util.Log.w("LedgerCapture", "Notification processing failed") }
       .getOrDefault(false)
     }
     continuation.resume(count)
    }
   }
  }
 }
 override fun onListenerConnected() {
  boundListener = this
  listenerConnection.value = true
 }
 override fun onListenerDisconnected() {
  clearConnection()
 }
 override fun onDestroy() {
  clearConnection()
  super.onDestroy()
 }
 private fun clearConnection() {
  if (boundListener !== this) return
  boundListener = null
  listenerConnection.value = false
 }
 override fun onNotificationPosted(sbn:StatusBarNotification){
  captureExecutor.execute {
   runCatching { capturePaymentNotification(Store.get(this), sbn) }
    .onFailure { android.util.Log.w("LedgerCapture","Notification processing failed") }
  }
 }
}

internal fun capturePaymentNotification(store: Store, sbn: StatusBarNotification): Boolean {
  if(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)return false
  val pkg=sbn.packageName
  if(store.setting("autoGuard","true")!="true" || store.setting("notifications","true")!="true" ||
      !store.notificationChannelEnabled(pkg))return false
  if(pkg !in paymentNotificationChannels.values &&
      store.setting("packages").split(",").none { it.trim()==pkg })return false
  val extra=sbn.notification.extras
  val shopping = pkg in shoppingPaymentApps
  val title=extra.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank {
   if(shopping) extra.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty() else ""
  }
  if(pkg=="com.tencent.mm"&&title.trim()!="微信支付")return false
  val text=extra.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val big=extra.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
  val sub=extra.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
  val body=big.ifBlank {
   if(shopping) extra.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty().ifBlank { text }
   else text
  }
  if(title.isBlank() && body.isBlank() && sub.isBlank())return false
  if(shopping && !isShoppingPayment(listOf(title,body,text,sub).joinToString("\n")))return false
  return store.ingest("notification",pkg,title,body,sbn.postTime,sbn.key,text,big,sub)!=null
}
private val captureExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
private val scanLock=Any()
private val smsAmount = Regex("""(?:人民币|RMB|[¥￥]|消费|支出|收入|入账|转账|扣款|退款|金额|存入)[：:为\s]*(?:人民币|RMB|[¥￥])?\s*\d[\d,.]*|\d[\d,.]*\s*元""",RegexOption.IGNORE_CASE)

fun acceptSms(c:Context,address:String,body:String,time:Long,simSlot:Int=-1){
 val store=Store.get(c)
 if(store.setting("autoGuard","true")!="true" || store.setting("sms","true")!="true")return
 val senders=listOf("95555","95533")+store.setting("senders").split(",").map(String::trim).filter(String::isNotEmpty)
 if(address !in senders || body.contains("验证码") || !tailPattern.containsMatchIn(body) || !smsAmount.containsMatchIn(body))return
 store.ingest("sms",address,"银行动账短信",body,time,simSlot=simSlot)
}
class SmsReceiver:BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent){
  if(i.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION)return
  val messages=Telephony.Sms.Intents.getMessagesFromIntent(i)
  val slot=i.getIntExtra("slot",i.getIntExtra("slot_id",-1))
  val pending=goAsync()
  captureExecutor.execute {
   try {
    messages.groupBy{it.originatingAddress}.forEach{(address,parts)->
     acceptSms(c,address.orEmpty(),parts.joinToString(""){it.messageBody.orEmpty()},parts.first().timestampMillis,slot)
    }
   } catch(_:Exception) {
    android.util.Log.w("LedgerCapture","SMS processing failed")
   } finally { pending.finish() }
  }
 }
}
fun readSms(c:Context):Int = synchronized(scanLock) {
 if(c.checkSelfPermission("android.permission.READ_SMS")!=PackageManager.PERMISSION_GRANTED)return@synchronized 0
 val store=Store.get(c)
 if(store.setting("autoGuard","true")!="true" || store.setting("sms","true")!="true")return@synchronized 0
 val until=System.currentTimeMillis()
 val from=store.setting("smsReadAt").toLongOrNull()?.coerceAtMost(until) ?: until-90L*86400000
 var count=0
 val cursor=c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
     arrayOf("address","body","date","date_sent"),"date >= ? AND date <= ?",
     arrayOf(from.toString(),until.toString()),"date ASC") ?: return@synchronized 0
 cursor.use {
  while(it.moveToNext()){
   val timestamp=it.getLong(3).takeIf { sent -> sent>0 } ?: it.getLong(2)
   acceptSms(c,it.getString(0).orEmpty(),it.getString(1).orEmpty(),timestamp)
   count++
  }
 }
 store.set("smsReadAt",until.toString())
 count
}
fun schedule(c:Context){scheduleReview(c);WorkManager.getInstance(c).enqueueUniquePeriodicWork("ledger-watch",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<WatchWorker>(6,TimeUnit.HOURS).build())}
class BootReceiver:android.content.BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action==Intent.ACTION_BOOT_COMPLETED)schedule(c)}}
class WatchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork(): Result = try {
  readSms(applicationContext)
  val store = Store.get(applicationContext)
  if (store.setting("autoGuard", "true") == "true" && store.setting("notifications", "true") == "true" &&
      !PaymentListener.connected && notificationAccess(applicationContext)) {
   val connected = runCatching { runBlocking { PaymentListener.reconnect(applicationContext) } }.getOrDefault(false)
   if (!connected) notifyUser(applicationContext, "噗噗需要恢复值班", "请打开数据来源，检查通知使用权", 12)
  }
  store.takeDaySnapshot()
  val cutoff = System.currentTimeMillis() - store.setting("retention", "180").toLong() * 86400000
  store.rows("RawEvent").filter { it.getLong("time") < cutoff }.forEach { store.delete(it.getString("id")) }
  Result.success()
 } catch (_: Exception) {
  Result.retry()
 }
}

private fun nextReview(c:Context): OneTimeWorkRequest {
 val now = ZonedDateTime.now()
 val time=runCatching { LocalTime.parse(Store.get(c).setting("reviewTime","21:30")) }.getOrDefault(LocalTime.of(21,30))
 var next=now.toLocalDate().atTime(time).atZone(now.zone)
 if(!next.isAfter(now)) next=now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
 return OneTimeWorkRequestBuilder<ReviewWorker>()
     .setInitialDelay(Duration.between(now,next).toMillis(),TimeUnit.MILLISECONDS).build()
}
fun scheduleReview(c:Context,replace:Boolean=false) {
 WorkManager.getInstance(c).enqueueUniqueWork("ledger-review",
     if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,nextReview(c))
}
class ReviewWorker(c:Context,p:WorkerParameters):Worker(c,p) {
 override fun doWork():Result {
  val s=Store.get(applicationContext)
  val today=LocalDate.now()
  val candidate=s.txns().filter { day(it.time)==today && it.direction=="支出" && it.category!="居住" && it.status!="已忽略" && it.score<0 }.maxByOrNull { it.cents }
  val key="reviewSent:$today"
  if(candidate!=null && s.setting(key)!="true"){
   notifyUser(applicationContext,"今天这笔花得值不值？","噗噗等你给今天的花销打个分",15)
   s.set(key,"true")
  }
  val month=YearMonth.from(today).toString()
  if(today.dayOfMonth<=3 && s.setting("monthReportSent")!=month){
   notifyUser(applicationContext,"上个月的花销月报","打开空叶记账看看上月的花销与心情",17)
   s.set("monthReportSent",month)
  }
  if(!isStopped)WorkManager.getInstance(applicationContext).enqueueUniqueWork("ledger-review",ExistingWorkPolicy.APPEND_OR_REPLACE,nextReview(applicationContext))
  return Result.success()
 }
}
