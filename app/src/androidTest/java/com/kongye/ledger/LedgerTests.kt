package com.kongye.ledger
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.os.Process
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
class LedgerTests:Instrumentation(){
 private var mode: String? = null
 private var screenPackage: String? = null
 private var expectedCents: Long = 0
 private var screenshotNow: Long? = null
 private var screenshotZone: String? = null
 override fun onCreate(arguments:Bundle?){
  super.onCreate(arguments)
  mode = arguments?.getString("mode")
  screenPackage = arguments?.getString("screenPackage")
  expectedCents = arguments?.getString("expectedCents")?.toLongOrNull() ?: 0
  screenshotNow = arguments?.getString("now")?.toLongOrNull()
  screenshotZone = arguments?.getString("timezone")
  start()
 }
 override fun onStart(){val result=Bundle();try{
 require(targetContext.packageName.endsWith(".qa")) { "Tests must use the isolated QA package" }
 if (mode == "screen-image") {
  val previousZone = java.util.TimeZone.getDefault()
  screenshotZone?.let { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(it)) }
  val directory = targetContext.getExternalFilesDir(null)
  val bitmap = android.graphics.BitmapFactory.decodeFile(java.io.File(directory,"screen-fixture.png").absolutePath)
   ?: error("Missing screenshot fixture")
  val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
   com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
  try {
   val text = com.google.android.gms.tasks.Tasks.await(recognizer.process(
    com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap,0)),30,java.util.concurrent.TimeUnit.SECONDS)
   val lines = text.textBlocks.flatMap { it.lines }
    .sortedWith(compareBy({it.boundingBox?.top ?: 0},{it.boundingBox?.left ?: 0})).joinToString("\n") { it.text }
   java.io.File(directory,"screen-ocr.txt").writeText(lines)
   val payment = ScreenPaymentParser.parse(requireNotNull(screenPackage),lines,screenshotNow ?: System.currentTimeMillis())
    ?: error("Screenshot payment not recognized")
   check(payment.cents == expectedCents) { "Screenshot payment amount mismatch" }
   java.io.File(directory,"screen-result.json").writeText(payment.json().toString())
   result.putString("stream", "PASS: captured screenshot recognized with the expected payment amount")
  } finally { recognizer.close(); bitmap.recycle(); java.util.TimeZone.setDefault(previousZone) }
  finish(RESULT_OK,result)
  return
 }
 if (mode == "listener") {
  verifyNotificationListener()
  result.putString("stream", "PASS: Android notification listener reconnected, received a notification and saved it automatically")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "shopping-payments") {
  verifyShoppingPayments()
  result.putString("stream", "PASS: shopping channels, amount and merchant extraction, duplicate filtering and unpaid notice rejection")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "screen-payments" || mode == "screen-ocr") {
  verifyScreenPayments(mode == "screen-ocr")
  result.putString("stream", "PASS: local screen recognition, amount and time, pending review, repeated screens and notification deduplication")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "budgets") {
  verifyBudgetPeriods()
  result.putString("stream", "PASS: live daily quota, stale snapshot recovery, past-day history and budget periods")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "manual-entry") {
  verifyManualEntry()
  result.putString("stream", "PASS: independent entry categories survive backup and restore, and arithmetic keeps cent precision")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "webdav-password") {
  verifySavedWebDavPassword()
  result.putString("stream", "PASS: WebDAV password is encrypted, reusable, scoped to the saved account, and replaceable")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "github-updates") {
  verifyGitHubUpdates()
  verifyLauncherIcon()
  result.putString("stream", "PASS: numeric version comparison, stable release history, official APK links, and rate-limit feedback")
  finish(RESULT_OK, result)
  return
 }
 if (mode == "github-updates-live") {
  val versions = LedgerUpdates().check(targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName.orEmpty())
  val releases = versions.getJSONArray("releases")
  check(releases.length() > 0)
  result.putString("stream", "PASS: GitHub returned ${releases.length()} releases, latest ${releases.getJSONObject(0).getString("tag")}, comparison ${versions.getInt("comparison")}")
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
 private fun verifyGitHubUpdates() {
  val repo = "https://github.com/Mayleaves/KongyeLedger"
  val tag = "v0.3.20"
  val catalog = """[
   {"tag_name":"v0.3.9","name":"Earlier release","assets":[{"state":"uploaded","browser_download_url":"https://example.invalid/KongyeLedger-v0.3.9.apk"}]},
   {"tag_name":"v0.4.0","draft":true},
   {"tag_name":"v0.5.0","prerelease":true},
   {"tag_name":"v0.3.20","name":"Current release","body":"Changes","published_at":"2026-11-19T16:01:00Z","assets":[
     {"state":"uploaded","browser_download_url":"$repo/releases/download/$tag/KongyeLedger-$tag.apk"}
   ]}
  ]"""
  var code = 200
  val updates = LedgerUpdates(OkHttpClient.Builder().addInterceptor { chain ->
   val request = chain.request()
   check(request.url.toString() == "https://api.github.com/repos/Mayleaves/KongyeLedger/releases?per_page=50")
   check(request.header("Authorization") == null)
   Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
    .message("Fixture").body(catalog.toResponseBody()).build()
  }.build())
  val result = updates.check("0.3.10")
  val history = result.getJSONArray("releases")
  check(result.getInt("comparison") > 0 && history.length() == 2)
  check(history.getJSONObject(0).getString("tag") == tag && history.getJSONObject(0).getBoolean("hasApk"))
  check(!history.getJSONObject(1).getBoolean("hasApk"))
  check(updates.check("0.3.20").getInt("comparison") == 0)
  check(updates.check("0.3.21").getInt("comparison") < 0)
  check(LedgerUpdates.githubUrl("release", "../other") == null)
  check(LedgerUpdates.githubUrl("repository", "") == repo)
  code = 403
  check(runCatching { updates.check("0.3.20") }.exceptionOrNull() is LedgerUpdateException)
 }

 private fun verifyLauncherIcon() {
  val icon = targetContext.packageManager.getApplicationIcon(targetContext.packageName)
  val preview = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
  icon.setBounds(0, 0, preview.width, preview.height)
  icon.draw(Canvas(preview))
  for ((x, y) in listOf(0 to 0, 199 to 0, 0 to 199, 199 to 199)) {
   check(preview.getPixel(x, y) == 0xfffde0d9.toInt()) { "Launcher background should cover every corner" }
  }
  targetContext.openFileOutput("launcher-preview.png", Context.MODE_PRIVATE).use { output ->
   check(preview.compress(Bitmap.CompressFormat.PNG, 100, output))
  }
 }

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

 private fun verifyManualEntry() {
  check(entryAmount("19.90+3.20−1.05") == 2205L)
  check(entryAmount("2+3×4−6÷2") == 1100L)
  check(entryAmount("0.1+0.2") == 30L && entryAmount("1÷8") == 13L)
  check(entryAmount("2.×3") == 600L)
  check(runCatching { entryAmount("1÷0") }.isFailure)
  check(runCatching { entryAmount("1+") }.isFailure)
  check(runCatching { entryAmount("9999999999×2") }.isFailure)
  val store = Store.get(targetContext)
  val previous = store.setting("manualCategories")
  try {
   store.set("manualCategories", "{\"支出\":[\"旧版测试分类\"]}")
   check("旧版测试分类" in store.manualCategories("支出"))
   val expense = "测试出${System.currentTimeMillis()}"
   val income = "测试入${System.currentTimeMillis()}"
   check(store.addManualCategory("支出", " $expense ") == expense)
   check(store.addManualCategory("收入", income) == income)
   check(expense in store.manualCategories("支出") && expense !in store.manualCategories("收入"))
   check(income in store.manualCategories("收入") && income !in store.manualCategories("支出"))
   check(runCatching { store.addManualCategory("支出", expense) }.isFailure)
   check(runCatching { store.addManualCategory("收入", "全部种类") }.isFailure)
   store.changeManualCategory("支出", expense, "up")
   check(store.manualCategories("支出").takeLast(2) == listOf(expense, "旧版测试分类"))
   store.changeManualCategory("支出", "餐饮", "delete")
   check("餐饮" !in store.manualCategories("支出"))
   store.changeManualCategory("收入", income, "up")
   check(store.manualCategories("收入").takeLast(2) == listOf(income, "收入"))
   val backup = store.backup()
   store.set("manualCategories", "{}")
   store.restore(backup)
   check(store.manualCategories("支出").takeLast(2) == listOf(expense, "旧版测试分类"))
   check("餐饮" !in store.manualCategories("支出") && income in store.manualCategories("收入"))
   val row = toWeb(Txn(cents = 1200, merchant = "测试", category = "餐饮"))
       .put("date", "2026-11-21").put("time", "09:08:07")
   val precise = fromWeb(row, null)
   check(toWeb(precise).getString("time") == "09:08:07" && precise.category == "餐饮")
   val withMillis = precise.copy(time = precise.time + 456)
   check(fromWeb(row, withMillis).time == withMillis.time)
   check(toWeb(fromWeb(row.put("time", "09:08"), null)).getString("time") == "09:08:00")
  } finally {
   store.set("manualCategories", previous)
  }
 }

 private fun verifyBudgetPeriods() {
  val store = Store.get(targetContext)
  val date = LocalDate.of(2024, 2, 29)
  val cycle = { day: LocalDate -> day.withDayOfMonth(1) to day.plusMonths(1).withDayOfMonth(1) }
  check(BudgetPeriod.DAY.bounds(date, cycle) == (date to LocalDate.of(2024, 3, 1)))
  check(BudgetPeriod.WEEK.bounds(date, cycle) == (LocalDate.of(2024, 2, 26) to LocalDate.of(2024, 3, 4)))
  check(BudgetPeriod.MONTH.bounds(date, cycle) == (LocalDate.of(2024, 2, 1) to LocalDate.of(2024, 3, 1)))
  check(BudgetPeriod.QUARTER.bounds(date, cycle) == (LocalDate.of(2024, 1, 1) to LocalDate.of(2024, 4, 1)))
  check(BudgetPeriod.YEAR.bounds(date, cycle) == (LocalDate.of(2024, 1, 1) to LocalDate.of(2025, 1, 1)))
  val today = LocalDate.now()
  val todayQuota = "quota:$today"
  val yesterday = today.minusDays(1)
  val exampleDate = LocalDate.of(2026, 11, 20)
  val keys = BudgetPeriod.entries.map { it.key } + listOf(todayQuota,"startDay", "quota:$yesterday")
  val oldSettings = keys.associateWith { store.setting(it) }
  try {
   BudgetPeriod.entries.forEach { store.set(it.key, "") }
   store.set("startDay", "15")
   check(store.budgetLimits().getValue(BudgetPeriod.MONTH) == 300_000L)
   check(store.budgetLimits().getValue(BudgetPeriod.WEEK) == 0L)
   check(BudgetPeriod.MONTH.bounds(LocalDate.of(2024, 3, 1),store::cycle) == (LocalDate.of(2024, 2, 15) to LocalDate.of(2024, 3, 15)))
   val fixture = listOf(
    Txn(cents=1000,merchant="本周",category="餐饮",time=date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
    Txn(cents=200,merchant="退款",category="餐饮",direction="退款",time=date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
    Txn(cents=30,merchant="上周",category="餐饮",time=date.minusDays(4).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
   )
   check(store.budgetSpent(date,fixture).getValue(BudgetPeriod.WEEK)==800L)
   check(store.budgetSpent(date,fixture).getValue(BudgetPeriod.QUARTER)==830L)
   store.set("startDay", "1")
   val earlierBill = Txn(cents=2500,merchant="餐饮",category="餐饮",
    time=exampleDate.minusDays(2).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
   val example = requireNotNull(store.calculateBudgetBasis(exampleDate,listOf(earlierBill)))
   check(example.limit==300000L && example.earlierSpending==2500L && example.daysLeft==11L && example.daily==27045L)
   check(store.calculateBudgetBasis(exampleDate,emptyList())?.daily==27272L)
   store.set(todayQuota, "27045")
   store.set("quota:$yesterday", "22222")
   val days = java.time.temporal.ChronoUnit.DAYS.between(today, today.withDayOfMonth(1).plusMonths(1))
   check(store.quota(today,emptyList())==300000L/days && store.quota(yesterday,emptyList())==22222L)
   val todayBill = earlierBill.copy(time=today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
   check(store.quota(today,listOf(todayBill))==store.quota(today,emptyList()))
   val budgets=BudgetPeriod.entries.associateWith { if(it==BudgetPeriod.DAY)100L else 0L }
   store.setBudgetLimits(budgets)
   check(store.budgetLimits()==budgets && store.quota(LocalDate.now())==100L)
   store.setBudgetLimits(budgets.mapValues { 0L })
   check(store.quota(LocalDate.now())==0L)
  } finally {
   oldSettings.forEach(store::set)
  }
 }

 private fun verifyScreenPayments(ocr: Boolean) {
  val store = Store.get(targetContext)
  store.db.clearAllTables()
  val today = LocalDate.now()
  val now = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
  val pkg = "com.tencent.mm"
  val card = "微信支付\n10:21\n微信支付有优惠\n优惠额度\n166元\n10:26\n美团\n使用零钱支付\n￥34.40\n交易详情\n我的账单"
  val payment = ScreenPaymentParser.parse(pkg, card, now) ?: error("Card not recognized")
  check(payment.cents == 3440L && payment.merchant == "美团")
  check(ScreenPaymentParser.parse(pkg, card.replace("￥34.40","34.40").replace("美团","美团美团"), now)?.cents == 3440L)
  check(payment.time == today.atTime(10,26).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
  check(ScreenPaymentParser.parse(pkg, "微信支付\n美团\n使用零钱支付\n￥34.40", now) == null)
  check(ScreenPaymentParser.parse(pkg, "微信支付\n昨天\n10:26\n美团\n使用零钱支付\n￥34.40", now) == null)
  check(ScreenPaymentParser.parse(pkg, card + "\n支付成功\n￥10.00", now) == null)
  check(ScreenPaymentParser.parse("com.example.chat", card, now) == null)
  val result = ScreenPaymentParser.parse(pkg, "支付成功\n￥12.34\n商户\n测试咖啡", now) ?: error("Payment result not recognized")
  check(result.merchant == "测试咖啡" && result.cents == 1234L)
  val alipay = "com.eg.android.AlipayGphone"
  val meituan = "com.sankuai.meituan"
  val checkout = ScreenPaymentParser.parse(alipay, "美团\n-34.40\n交易成功\n付款方式\n余额宝\n交易时间\n" +
   today.toString() + " 10:26:18\n订单号\n12345678901234", now) ?: error("Alipay detail not recognized")
  check(checkout.merchant == "美团" && checkout.cents == 3440L)
  check(checkout.time == today.atTime(10,26,18).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
  check(ScreenPaymentParser.parse(alipay, "付款成功\n12.3\n收款商户\n测试咖啡店", now)?.cents == 1230L)
  val autoDebit = ScreenPaymentParser.parse(alipay,
   "账单详情\n测试代付\n-22.80\n自动扣款成功\n管理自动扣款\n自动扣款 免密支付\n支付时间\n${today} 10:17:53\n付款方式\n建设银行储蓄卡(0000)\n支付奖励\n立即领取15积分", now)!!
  check(autoDebit.cents == 2280L && autoDebit.merchant == "测试代付")
  check(autoDebit.time == today.atTime(10,17,53).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
  check(ScreenPaymentParser.parse(meituan, "支付成功\n实付金额\n￥23.50\n商家\n测试面馆", now)?.merchant == "测试面馆")
  check(ScreenPaymentParser.parse(meituan, "美团支付\n支付成功\n¥12.34", now)?.merchant == "美团")
  val meituanDetail = ScreenPaymentParser.parse(meituan,
   "账单详情\n测试面馆\n-64.95\n极速支付成功\n1笔返利待领取\n支付方式\n中国建设银行储蓄卡(0000)\n下单时间\n${LocalDate.now()} 10:26:18\n下单平台\n美团App\n消费场景\n外卖", now)!!
  check(meituanDetail.cents == 6495L && meituanDetail.merchant == "测试面馆")
  check(ScreenPaymentParser.parse(meituan,"测试面馆\n-64.95\n板逮支付成功", now)?.cents == 6495L)
  check(ScreenPaymentParser.parse(meituan,"测试面馆\n-64.95\n未支付成功", now) == null)
  check(ScreenPaymentParser.parse(meituan,"测试面馆\n-64.95\n支付成功后可领取", now) == null)
  val wechatDetail = ScreenPaymentParser.parse(pkg,
   "16688电商平台\n-9.77\n当前状态\n支付成功\n${today} 09:57:55\n支付时间\n商户全称\n测试网络科技有限公司", now)!!
  check(wechatDetail.merchant == "16688电商平台" && wechatDetail.cents == 977L)
  check(wechatDetail.time == today.atTime(9,57,55).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
  check(java.time.Instant.ofEpochMilli(meituanDetail.time).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString() == "10:26:18")
  check(ScreenPaymentParser.parse(meituan, "待付款\n实付金额 ￥23.50\n商家 测试面馆", now) == null)
  check(ScreenPaymentParser.parse(alipay, "退款成功\n¥12.34\n商户\n测试店", now) == null)
  check(ScreenPaymentParser.parse(alipay, "交易成功\n￥12.34\n￥18.90\n商户\n测试店", now) == null)
  check(ScreenPaymentParser.parse(alipay, "交易成功\n￥12.34\n商户\n测试店\n交易时间\n昨天10:26", now) == null)
  val key = "screen-fixture"
  val item = store.ingest("screen", pkg, "屏幕识别", payment.json().toString(), payment.time, key)!!
  check(item.status == "待确认" && item.cents == 3440L)
  check(store.ingest("screen", pkg, "屏幕识别", payment.json().put("time",payment.time+1000).toString(), payment.time+1000,key) == null)
  store.ingest("notification",pkg,"微信支付","向美团支付34.40元",payment.time+1000,"fixture-wx")
  check(store.txns().size == 1 && store.txns().single().events.size == 2)
  if (ocr) {
   val file=java.io.File(targetContext.getExternalFilesDir(null),"screen-fixture.png")
   val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: error("Missing screenshot fixture")
   val recognizer=com.google.mlkit.vision.text.TextRecognition.getClient(
    com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
   try {
    val text=com.google.android.gms.tasks.Tasks.await(recognizer.process(
     com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap,0)),30,java.util.concurrent.TimeUnit.SECONDS)
    val lines=text.textBlocks.flatMap{it.lines}.sortedWith(compareBy({it.boundingBox?.top?:0},{it.boundingBox?.left?:0})).joinToString("\n"){it.text}
    val found=ScreenPaymentParser.parse(pkg,lines,now) ?: error("Payment card could not be recognized from screenshot")
    check(found.cents==3440L && found.merchant=="美团")
   }finally{recognizer.close();bitmap.recycle()}
  }
 }

 private fun verifyShoppingPayments() {
  val context = targetContext
  val store = Store.get(context)
  store.db.clearAllTables()
  store.set("autoGuard", "true")
  store.set("notifications", "true")
  shoppingPaymentApps.keys.forEach { store.set("channel:$it", "true") }
  val time = System.currentTimeMillis()
  fun notice(channel: String, id: Int, title: String, body: String, expanded: Boolean = false): StatusBarNotification {
   val pkg = paymentNotificationChannels.getValue(channel)
   val builder = Notification.Builder(context, "fixture").setContentTitle(title)
   if (expanded) builder.setStyle(Notification.InboxStyle().addLine(body))
   else builder.setContentText(body)
   return StatusBarNotification(pkg, pkg, id, null, Process.myUid(), Process.myPid(), 0,
    builder.build(), Process.myUserHandle(), time)
  }
  val douyin = notice("douyin", 1, "订单支付成功", "店铺：测试好物；实付金额 ￥23.50，原价 ￥30.00")
  val xianyu = notice("xianyu", 2, "你已付款", "卖家：测试二手店，48.00元，运费 5.00元", expanded = true)
  val alibaba = notice("alibaba", 3, "1688 付款成功", "交易金额：￥108.40")
  listOf(douyin, xianyu, alibaba).forEach { check(capturePaymentNotification(store, it)) }
  val txns = store.txns().associateBy { it.cents }
  check(txns.keys == setOf(2350L, 4800L, 10840L))
  check(txns.values.all { it.direction == "支出" && it.source == "自动" && it.category == "购物" })
  check(txns.getValue(2350L).merchant == "测试好物" && txns.getValue(2350L).status == "已入账")
  check(txns.getValue(4800L).merchant == "测试二手店" && txns.getValue(4800L).status == "已入账")
  check(txns.getValue(10840L).merchant == "阿里巴巴" && txns.getValue(10840L).status == "待确认")
  check(!capturePaymentNotification(store, douyin))
  val ignored = listOf(
   notice("douyin", 4, "抖音商城", "付款成功后可参与抽奖，奖品价值 ¥89.00"),
   notice("xianyu", 5, "订单待付款", "实付金额：￥40.00，请付款"),
   notice("alibaba", 6, "退款成功", "订单已付款 ￥50.00，退款金额 ￥50.00"),
   notice("xianyu", 7, "卖家发货了", "订单金额 ¥12.00"),
   notice("douyin", 8, "付款成功", "订单金额属于两个订单，分别是 ￥10.00 和 ￥20.00"),
   notice("douyin", 11, "抖音", "付款成功 2026年11月24日 12:10")
  )
  check(ignored.none { capturePaymentNotification(store, it) })
  check(store.rows("RawEvent").size == 5) { "Shopping promos and routine notifications must not be stored" }
  check(capturePaymentNotification(store, notice("meituan", 12, "支付成功", "商家：测试面馆，实付 ￥26.80")))
  check(store.txns().single { it.cents == 2680L }.category == "餐饮")
  store.set("channel:" + paymentNotificationChannels.getValue("douyin"), "false")
  check(!capturePaymentNotification(store, notice("douyin", 9, "付款成功", "付款金额 ￥11.20")))
  store.set("autoGuard", "false")
  check(!capturePaymentNotification(store, notice("alibaba", 10, "付款成功", "付款金额 ￥13.20")))
  check(store.txns().size == 4)
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
   PaymentListener.requestConnection(context)
   awaitCondition("Android did not connect PaymentListener") { PaymentListener.connected }
   if (Build.VERSION.SDK_INT >= 34) {
    NotificationListenerService.requestUnbind(paymentListenerComponent(context))
    awaitCondition("Android did not disconnect PaymentListener") { !PaymentListener.connected }
    check(notificationAccess(context)) { "The system should keep notification permission while disconnected" }
   }
   check(runBlocking { PaymentListener.reconnect(context) }) { "The listener did not reconnect" }
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
   check(runBlocking { PaymentListener.checkTodaysNotifications(context) } == 0) { "Scanning active notifications must not duplicate a saved payment" }
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
