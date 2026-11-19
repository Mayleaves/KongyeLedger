package com.kongye.ledger
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import java.time.LocalDate
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
class LedgerTests:Instrumentation(){
 private var mode: String? = null
 override fun onCreate(arguments:Bundle?){
  super.onCreate(arguments)
  mode = arguments?.getString("mode")
  start()
 }
 override fun onStart(){val result=Bundle();try{
 require(targetContext.packageName.endsWith(".qa")) { "Tests must use the isolated QA package" }
 if (mode == "listener") {
  verifyNotificationListener()
  result.putString("stream", "PASS: Android notification delivered to PaymentListener, recognized and saved automatically")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "webdav-password") {
  verifySavedWebDavPassword()
  result.putString("stream", "PASS: WebDAV password is encrypted, reusable, scoped to the saved account, and replaceable")
  finish(RESULT_OK, result)
  return
 }
 val s=Store.get(targetContext);s.db.clearAllTables();val now=System.currentTimeMillis()
 fun check(ok:Boolean,message:String){if(!ok)throw AssertionError(message)}
 check(cents("12.34")==1234L,"money precision")
 check(entryAmount("19.90+3.20−1.05")==2205L,"manual keypad arithmetic")
 check(runCatching{entryAmount("1.00+")}.isFailure,"unfinished expression")
 val parsed=s.ingest("notification","com.tencent.mm","微信支付","向瑞幸支付12.34元",now,"fixture1")!!
 check(parsed.cents==1234L&&parsed.merchant=="瑞幸"&&parsed.category=="餐饮","parse")
 check(s.ingest("notification","com.tencent.mm","微信支付","向瑞幸支付12.34元",now,"fixture1")==null,"raw dedupe")
 s.ingest("sms","95555","银行动账短信","尾号1234消费人民币12.34元，在瑞幸",now)
 check(s.txns().size==1&&s.txns().first().events.size==2,"cross-source merge")
 val samePrice=s.ingest("notification","com.eg.android.AlipayGphone","支付成功","商户：地铁，实付 ¥12.34",now+1000,"fixture2")!!
 check(samePrice.status=="待确认" && s.txns().size==2,"different merchants never merge merely because price matches")
 val withBalance=s.ingest("sms","95533","银行动账短信","卡尾号5678，余额人民币798.02元，于2026-11-11 08:09:10消费人民币3.50元，在瑞幸",now+2000)!!
 check(withBalance.cents==350L && withBalance.time < now && withBalance.account=="5678","SMS amount ignores balance and uses body time")
 val needsRules=s.ingest("notification","com.eg.android.AlipayGphone","支付","新格式 42.50，店铺：瑞幸",now,"fixture3")
 check(needsRules==null && s.rows("RawEvent").any { it.optString("reason").contains("金额") },"failed parse retained")
 val defaultRules=targetContext.assets.open("parser.json").bufferedReader().use { it.readText() }
 val updated=org.json.JSONArray(defaultRules)
 val custom=JSONObject().put("source","notification")
   .put("senders",org.json.JSONArray(listOf("com.eg.android.AlipayGphone")))
   .put("amount","新格式 ([0-9.]+)").put("merchant","店铺：([^，。]+)")
 updated.put(custom)
 s.importParser(updated.toString())
 check(s.reparse().contains("找回 1 条"),"rule import and historical reparse")
 check(s.reparse().contains("找回 0 条"),"reparse idempotence")
 s.importParser(defaultRules)
 s.split(s.txns().first { it.events.size==2 });check(s.txns().size==5,"split")
 val sale=Txn(cents=520,merchant="地铁",category="交通",time=now-60000)
 s.save(sale)
 val refund=Txn(cents=520,merchant="地铁",category="收入",time=now,direction="退款")
 s.linkRefund(refund)
 check(refund.refundOf==sale.id,"refund links to original")
 s.db.clearAllTables()
 val bill="交易时间,交易类型,交易对方,商品,收/支,金额(元),交易状态,交易单号\n2026-11-10 12:30:00,支付,瑞幸,咖啡,支出,19.90,支付成功,wx123\n"
 BillImport.run(s,bill.toByteArray());check(s.txns().size==1,"import")
 BillImport.run(s,bill.toByteArray());check(s.txns().size==1,"idempotent import")
 val backup=s.backup();s.db.clearAllTables();s.restore(backup);check(s.txns().size==1,"backup restore")
 val account=LedgerDavAccount("https://example.invalid/dav/账本","fixture","app-key")
 check(account.directory.pathSegments.last().isEmpty(),"WebDAV accepts directories with no trailing slash")
 check(account.backupDirectory.encodedPath.endsWith("/KongyeLedger/"),"WebDAV folder name")
 val directAccount=LedgerDavAccount(account.backupDirectory.toString(),"fixture","app-key")
 check(directAccount.file==account.file && directAccount.legacyFile==null,"WebDAV doesn't nest an explicitly named folder")
 val methods=mutableListOf<String>()
 var remoteBackup:String?=null
 var legacyBackup:String?=null
 var folderExists=false
 val fakeDav=OkHttpClient.Builder().addInterceptor { chain ->
  val request=chain.request()
  check(request.header("Authorization")==okhttp3.Credentials.basic("fixture","app-key"),"WebDAV basic authentication")
  methods.add(request.method)
  val (code,body)=when(request.method) {
   "PROPFIND" -> {
    check(request.header("Depth")=="0","WebDAV probe depth")
    val code=when(request.url) {
     account.directory -> 207
     account.backupDirectory -> if(folderExists)207 else 404
     else -> error("unexpected probe path")
    }
    code to ""
   }
   "MKCOL" -> {
    check(request.url==account.backupDirectory && !folderExists,"WebDAV creates the backup folder once")
    folderExists=true
    201 to ""
   }
   "PUT" -> {
    check(request.url==account.file && folderExists,"WebDAV upload path")
    remoteBackup=Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
    201 to ""
   }
   "GET" -> {
    val contents=when(request.url) {
     account.file -> remoteBackup
     account.legacyFile -> legacyBackup
     else -> error("unexpected download path")
    }
    contents?.let { 200 to it } ?: (404 to "")
   }
   else -> error("unexpected request")
  }
  Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
   .message("Fixture").body(body.toResponseBody()).build()
 }.build()
 val dav=LedgerWebDav(fakeDav)
 check(runCatching { dav.download(account) }.exceptionOrNull() is LedgerDavException,"missing remote backup")
 legacyBackup=backup
 check(dav.download(account)==backup,"restore falls back to the old location")
 methods.clear()
 dav.test(account)
 dav.upload(account,backup)
 dav.upload(account,backup)
 s.db.clearAllTables()
 s.restore(dav.download(account))
 check(s.txns().size==1 && methods==listOf("PROPFIND","PROPFIND","MKCOL","PUT","PROPFIND","PUT","GET"),"WebDAV round trip")
 check(runCatching { LedgerDavAccount("http://example.invalid/dav/","fixture","app-key") }.isFailure,"WebDAV requires HTTPS")
 verifySavedWebDavPassword()
 val malformed=JSONObject(backup)
 val invalidRecord=JSONObject().put("id","bad").put("kind","Txn").put("payload",JSONObject())
 malformed.getJSONArray("records").put(invalidRecord)
 check(runCatching { s.restore(malformed.toString()) }.isFailure && s.txns().size==1,"reject malformed backup")
 s.db.clearAllTables();s.set("budget","300000");s.set("startDay","1")
 val today=LocalDate.now();val q=s.quota(today);s.save(Txn(cents=10000,merchant="测试",category="餐饮"));check(s.quota(today)==q,"daily quota fixed")
 check(s.spent(today)==10000L,"daily totals");check(BillImport.csv("a,b\n\"x,y\",\"z\"\"q\"").last()==listOf("x,y","z\"q"),"CSV quoting")
 s.db.clearAllTables();s.set("onboarded","false");result.putString("stream","PASS: keypad, parsing, dedupe, import, local and WebDAV backup, saved WebDAV password, malformed backup, and daily quota")
 finish(RESULT_OK,result)
 }catch(t:Throwable){result.putString("stream",t.stackTraceToString());finish(0,result)}}
 private fun verifySavedWebDavPassword() {
  val preferences = targetContext.getSharedPreferences("ledger_webdav", Context.MODE_PRIVATE)
  val previous = preferences.all
  val address = "https://example.invalid/dav/"
  val user = "fixture"
  val password = "密码 !\"&42"
  try {
   check(preferences.edit().clear().putString("address", address).putString("user", user).commit())
   val settings = LedgerDavSettings(targetContext)
   check(settings.publicConfig().getString("address") == address && !settings.publicConfig().getBoolean("hasPassword"))
   check(settings.save(settings.account(address, user, password, false)))
   val reopened = LedgerDavSettings(targetContext)
   val config = reopened.publicConfig()
   check(config.length() == 3 && config.getBoolean("hasPassword"))
   check(preferences.getString("encryptedPassword", "").orEmpty().isNotEmpty())
   check(preferences.all.values.none { it.toString().contains(password) })
   check(!Store.get(targetContext).backup().contains("encryptedPassword"))
   check(reopened.account(address.removeSuffix("/"), user, "", true).password == password)
   check(runCatching { reopened.account("https://elsewhere.invalid/dav/", user, "", true) }.isFailure)
   check(runCatching { reopened.account(address, "other", "", true) }.isFailure)
   check(reopened.save(reopened.account(address, user, "updated", false)))
   check(reopened.account(address, user, "", true).password == "updated")
   check(reopened.save(reopened.account(address, user, "", false)))
   check(!reopened.publicConfig().getBoolean("hasPassword"))
  } finally {
   val editor = preferences.edit().clear()
   previous.forEach { (key, value) -> editor.putString(key, value as String) }
   check(editor.commit())
  }
 }

 private fun verifyNotificationListener() {
  val context = targetContext
  check(notificationAccess(context)) { "Enable notification access for the isolated QA package first" }
  val store = Store.get(context)
  val manager = context.getSystemService(NotificationManager::class.java)
  check(manager.areNotificationsEnabled()) { "Enable notifications for the isolated QA package first" }
  val channels = listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay")
  val options = mapOf(
   "autoGuard" to "true", "notifications" to "true", "sms" to "false", "packages" to context.packageName,
   "reminders" to "false"
  ) + channels.associate { "channel:$it" to "false" }
  val previous = options.keys.associateWith { key -> store.setting(key, if (key == "packages") "" else "true") }
  val merchant = "瑞幸联调${SystemClock.elapsedRealtime()}"
  val channelId = "ledger-qa-listener"
  val notificationId = 40241
  try {
   options.forEach(store::set)
   awaitCondition("Android did not connect PaymentListener") { PaymentListener.connected }
   manager.createNotificationChannel(NotificationChannel(channelId, "账本联调测试", NotificationManager.IMPORTANCE_LOW))
   manager.notify(notificationId, Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_launcher)
    .setContentTitle("支付成功")
    .setContentText("向${merchant}支付12.34元")
    .build())
   awaitCondition("No transaction was saved from the Android notification") { store.txns().any { it.merchant == merchant } }
   val txn = store.txns().single { it.merchant == merchant }
   check(txn.cents == 1234L && txn.direction == "支出" && txn.category == "餐饮")
   check(txn.source == "自动" && txn.status == "已入账" && txn.events.size == 1)
   val event = store.rows("RawEvent").single { it.optString("id") == txn.events.single() }
   check(event.optString("sender") == context.packageName && event.optString("source") == "notification")
  } finally {
   manager.cancel(notificationId)
   manager.deleteNotificationChannel(channelId)
   store.txns().filter { it.merchant == merchant }.forEach { item ->
    item.events.forEach(store::delete)
    store.delete(item.id)
   }
   previous.forEach(store::set)
  }
 }

 private fun awaitCondition(message: String, condition: () -> Boolean) {
  val deadline = SystemClock.elapsedRealtime() + 15_000
  while (!condition()) {
   check(SystemClock.elapsedRealtime() < deadline) { message }
   SystemClock.sleep(120)
  }
 }
 companion object {const val RESULT_OK=-1}
}
