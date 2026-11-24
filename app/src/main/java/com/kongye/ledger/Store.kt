package com.kongye.ledger

import android.content.*
import androidx.room.Room
import org.json.*
import java.time.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*

val Context.settingsStore by preferencesDataStore("settings")
data class Txn(val id:String=UUID.randomUUID().toString(),var cents:Long,var merchant:String,var category:String,var direction:String="支出",var time:Long=System.currentTimeMillis(),var source:String="手动",var status:String="已入账",var serial:String="",var account:String="",var note:String="",var score:Int=-1,var events:MutableList<String> = mutableListOf(),var refundOf:String="") {
 fun json()=JSONObject().put("id",id).put("cents",cents).put("merchant",merchant).put("category",category).put("direction",direction).put("time",time).put("source",source).put("status",status).put("serial",serial).put("account",account).put("note",note).put("score",score).put("events",JSONArray(events)).put("refundOf",refundOf)
 companion object { fun from(j:JSONObject)=Txn(j.getString("id"),j.getLong("cents"),j.optString("merchant"),j.optString("category"),j.optString("direction","支出"),j.getLong("time"),j.optString("source"),j.optString("status"),j.optString("serial"),j.optString("account"),j.optString("note"),j.optInt("score",-1),j.optJSONArray("events")?.let { a->(0 until a.length()).map{a.getString(it)}.toMutableList() }?:mutableListOf(),j.optString("refundOf")) }
}
fun money(c:Long)=BigDecimal.valueOf(c,2).toPlainString()
fun cents(s:String):Long=BigDecimal(s.replace(",","").replace("¥","").replace("￥","").trim()).movePointRight(2).longValueExact()
fun day(t:Long)=Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
val categories=listOf("餐饮","交通","购物","居住","娱乐","医疗","学习","人情","收入","其他")
class Store private constructor(val ctx:Context) {
 val db=Room.databaseBuilder(ctx,LedgerDb::class.java,"ledger.db").allowMainThreadQueries().build()
 val revision=MutableStateFlow(0)
 @Volatile var prefs:Preferences=runBlocking {ctx.settingsStore.data.first()}; private set
 fun setting(key:String,default:String="")=prefs[stringPreferencesKey(key)]?:default
 fun set(key:String,value:String) {runBlocking {ctx.settingsStore.edit {it[stringPreferencesKey(key)]=value}}; prefs=runBlocking{ctx.settingsStore.data.first()}; changed()}
 fun changed(){revision.update{it+1}}
 fun rows(kind:String)=db.records().all(kind).map{JSONObject(it.payload)}
 fun put(kind:String,id:String,j:JSONObject){ val r=LedgerDb.Record();r.id=id;r.kind=kind;r.payload=j.toString();db.records().put(r);changed() }
 fun txns()=rows("Txn").map{Txn.from(it)}.sortedByDescending{it.time}
 fun save(t:Txn){
  put("Txn",t.id,t.json())
  val today=LocalDate.now()
  if(t.direction!="支出" || t.status=="已忽略" || day(t.time)!=today)return
  val entries=txns()
  val daily=quota(today,entries)
  val amount=entries.filter{it.status!="已忽略"&&day(it.time)==today}.sumOf(::netExpense)
  if(daily>0&&amount>=daily*4/5&&setting("budgetAlert:$today")!="sent"){
   set("budgetAlert:$today","sent")
   notifyUser(ctx,"今天的额度快用完啦","打开空叶记账，看看接下来的安排",14)
  }
  val limit=categoryBudget(t.category,today)
  if(limit>0){
   val (start,end)=cycle(today)
   val used=entries.filter{it.category==t.category&&it.status!="已忽略"&&day(it.time) in start..<end}.sumOf(::netExpense)
   val key="categoryAlert:$start:${t.category}"
   if(used>=limit-limit/10 && setting(key)!="sent"){
    set(key,"sent")
    notifyUser(ctx,"分类预算已接近上限","打开预算查看本期使用情况",16)
   }
  }
 }
 fun delete(id:String){db.records().delete(id);changed()}
 fun rule(word:String,cat:String,learned:Boolean=false)=put("Rule","rule:$word",JSONObject().put("word",word).put("cat",cat).put("learned",learned).put("created",System.currentTimeMillis()))
 fun classify(m:String):String {
  rows("Rule").sortedByDescending{it.getString("word").length}.firstOrNull{m.contains(it.getString("word"),true)}?.let{return it.getString("cat")}
  val built=mapOf("餐饮" to listOf("瑞幸","美团","饿了么","餐","咖啡"),"交通" to listOf("滴滴","地铁","公交","高德"),"购物" to listOf("淘宝","京东","盒马","超市"),"居住" to listOf("房租","水费","电费"),"娱乐" to listOf("电影","游戏"))
  val available=categoryChoices().map { it.name }.toSet()
  return built.entries.firstOrNull{(category,words)->category in available && words.any{m.contains(it)}}?.key?:"其他"
 }
 fun parserText():String = setting("parserRules").ifBlank { ctx.assets.open("parser.json").bufferedReader().use { it.readText() } }

 @Synchronized fun importParser(text:String) {
  LedgerParser(text)
  set("parserRules", text)
  set("parserVersion", (setting("parserVersion","2").toInt()+1).toString())
  parserCache = null
 }

 private var parserCache: Pair<String, LedgerParser>? = null
 private fun parser():LedgerParser {
  val version = setting("parserVersion","2") + ":" + setting("parserRules").hashCode()
  parserCache?.takeIf { it.first == version }?.let { return it.second }
  return LedgerParser(parserText()).also { parserCache = version to it }
 }

 @Synchronized fun ingest(source:String,sender:String,title:String,body:String,time:Long,key:String="",
     text:String=body,bigText:String="",subText:String="",simSlot:Int=-1):Txn? {
  val data = listOf(source,sender,if(source=="sms" || key.isBlank()) time.toString() else key,title,body,text,bigText,subText)
  val hash=MessageDigest.getInstance("SHA-256").digest(JSONArray(data).toString().toByteArray()).joinToString(""){"%02x".format(it)}
  val id="raw:$hash"
  if(db.records().find(id)!=null)return null
  val legacy=MessageDigest.getInstance("SHA-256").digest("$source|$sender|${if(source=="sms") time else key}|$title\n$body".toByteArray()).joinToString(""){"%02x".format(it)}
  if(db.records().find("raw:$legacy")!=null)return null
  val raw=JSONObject().put("id",id).put("source",source).put("sender",sender).put("title",title).put("body",body)
      .put("time",time).put("receivedAt",System.currentTimeMillis()).put("hash",hash).put("key",key)
      .put("text",text).put("bigText",bigText).put("subText",subText).put("simSlot",simSlot)
  put("RawEvent",id,raw)
  return process(raw, remind=true)
 }

 private fun process(raw:JSONObject,remind:Boolean):Txn? {
  val result=parser().parse(raw)
  val id=raw.getString("id")
  fun record(status:String,reason:String) {
   raw.put("parserVersion",setting("parserVersion","2").toIntOrNull()?:2)
       .put("status",status).put("reason",reason).put("confidence",result.confidence)
   put("RawEvent",id,raw)
  }
  val txn=result.transaction
  if(txn==null){record("未识别",result.reason);return null}
  val all=txns()
  txn.category=if(txn.direction in listOf("收入","退款")) "收入"
      else classify(txn.merchant).takeUnless { it=="其他" } ?: txn.category
  val similar=all.filter { it.status!="已忽略" && it.cents==txn.cents && it.direction==txn.direction &&
      kotlin.math.abs(it.time-txn.time)<=180000 }
  val forbidden=if(similar.isNotEmpty())rows("NoMerge")else emptyList()
  val source=raw.getString("source")
  val sender=raw.getString("sender")
  val matches=similar.filter { candidate ->
   val related=candidate.events.mapNotNull { event -> db.records().find(event)?.payload?.let(::JSONObject) }
   val sameOrigin=related.any { it.optString("source")==source && it.optString("sender")==sender }
   val differentSource=related.any { it.optString("source")!=source || it.optString("sender")!=sender }
   val sameSerial=txn.serial.isNotBlank() && txn.serial==candidate.serial
   val sameAccount=txn.account.isNotBlank() && txn.account==candidate.account
   val conflictingSerial=txn.serial.isNotBlank() && candidate.serial.isNotBlank() && txn.serial!=candidate.serial
   val conflictingAccount=txn.account.isNotBlank() && candidate.account.isNotBlank() && txn.account!=candidate.account
   val txnMerchantUnknown=txn.merchant=="待确认商户" || txn.merchant==shoppingPaymentApps[sender]
   val candidateMerchantUnknown=candidate.merchant=="待确认商户" || related.any {
    candidate.merchant==shoppingPaymentApps[it.optString("sender")]
   }
   val sameMerchant=!txnMerchantUnknown && !candidateMerchantUnknown &&
       (txn.merchant.contains(candidate.merchant,true)||candidate.merchant.contains(txn.merchant,true))
   val blocked=forbidden.any { pair -> (pair.optString("a")==id && pair.optString("b") in candidate.events) ||
       (pair.optString("b")==id && pair.optString("a") in candidate.events) }
   candidate.source=="自动" && !blocked && !sameOrigin && !conflictingSerial && !conflictingAccount &&
       (sameSerial || differentSource && (sameAccount && (sameMerchant || txnMerchantUnknown || candidateMerchantUnknown) || sameMerchant))
  }
  val existing=matches.singleOrNull()?.takeIf { similar.size==1 }
  if(existing!=null && remind){
   existing.events.add(id)
   if(txn.merchant!="待确认商户" && (existing.merchant=="待确认商户" || txn.merchant.length>existing.merchant.length)){
    existing.merchant=txn.merchant
    if(existing.category=="其他") existing.category=txn.category
   }
   if(existing.account.isBlank())existing.account=txn.account
   if(existing.serial.isBlank())existing.serial=txn.serial
   put("Txn",existing.id,existing.json())
   record("已合并","金额、时间及商户或账户吻合")
   return existing
  }
  linkRefund(txn,all)
  txn.status=if(similar.isNotEmpty() || result.confidence<80 || txn.category=="其他" || txn.direction=="转账") "待确认" else "已入账"
  if(remind)save(txn)else put("Txn",txn.id,txn.json())
  record(txn.status,if(similar.isNotEmpty())"存在相近账目，等待核对"else result.reason)
  if(remind && txn.status=="待确认") notifyUser(ctx,"噗噗抓到一笔新账目","打开空叶记账，确认这笔账目",11)
  return txn
 }

 @Synchronized fun reparse():String {
  val used=txns().flatMapTo(mutableSetOf()){it.events}
  val samples=rows("RawEvent").filter { it.optString("status") in listOf("","未识别") && it.optString("id") !in used }.sortedBy { it.optLong("time") }
  val recovered=samples.count { process(it,remind=false)!=null }
  return "已重新解析 ${samples.size} 条未识别样本，找回 $recovered 条。已有账目保持原样。"
 }

 fun spent(date:LocalDate)=txns().filter{it.status!="已忽略"&&day(it.time)==date}.sumOf{when(it.direction){"支出"->it.cents;"退款"->-it.cents;else->0L}}
 fun cycle(date:LocalDate):Pair<LocalDate,LocalDate>{val n=setting("startDay","1").toIntOrNull()?.coerceIn(1,28)?:1;var start=date.withDayOfMonth(n);if(start>date)start=start.minusMonths(1);return start to start.plusMonths(1)}
 fun categoryBudget(category:String,date:LocalDate=LocalDate.now()):Long =
  setting("categoryBudget:${cycle(date).first}:$category").toLongOrNull()?:0
 fun setCategoryBudget(category:String,amount:Long,date:LocalDate=LocalDate.now()){
  require(amount>=0)
  set("categoryBudget:${cycle(date).first}:$category",amount.toString())
 }

 internal fun budgetLimits(): Map<BudgetPeriod, Long> = BudgetPeriod.entries.associateWith { period ->
  val fallback = if (period == BudgetPeriod.MONTH) 300_000L else 0L
  setting(period.key).toLongOrNull()?.takeIf { it >= 0 } ?: fallback
 }

 internal fun budgetSpent(date: LocalDate, entries: List<Txn>): Map<BudgetPeriod, Long> =
  BudgetPeriod.entries.associateWith { period ->
   val (start, _) = period.bounds(date, ::cycle)
   budgetExpenses(start, date.plusDays(1), entries).coerceAtLeast(0)
  }

 @Synchronized internal fun setBudgetLimits(limits: Map<BudgetPeriod, Long>) {
  require(limits.keys == BudgetPeriod.entries.toSet() && limits.values.all { it in 0..999_999_999_999L })
  val today = LocalDate.now()
  val basis = calculateBudgetBasis(today, txns(), limits)
  prefs = runBlocking {
   ctx.settingsStore.edit { values ->
    limits.forEach { (period, amount) -> values[stringPreferencesKey(period.key)] = amount.toString() }
    values[stringPreferencesKey("quota:$today")] = (basis?.daily ?: 0L).toString()
   }
  }
  changed()
 }

 private fun budgetExpenses(start: LocalDate, end: LocalDate, entries: List<Txn>): Long =
  entries.filter { it.status != "已忽略" && day(it.time) in start..<end }.sumOf(::netExpense)

 internal fun calculateBudgetBasis(date: LocalDate, entries: List<Txn>, limits: Map<BudgetPeriod, Long> = budgetLimits()): DailyBudgetBasis? =
  limits.asSequence().filter { it.value > 0 }.map { (period, amount) ->
   val (start, end) = period.bounds(date, ::cycle)
   val used = budgetExpenses(start, date, entries)
   DailyBudgetBasis(period, amount, used, java.time.temporal.ChronoUnit.DAYS.between(date, end))
  }.minByOrNull { it.daily }

 fun quota(date: LocalDate, entries: List<Txn> = txns()): Long {
  // Old daily snapshots still apply to past dates. Today's amount reflects corrected and imported bills.
  if (date < LocalDate.now()) setting("quota:$date").toLongOrNull()?.let { return it }
  return calculateBudgetBasis(date, entries)?.daily ?: 0L
 }
 private fun computedMood(date:LocalDate,spent:Long,entries:List<Txn>):String {
  val limit = quota(date,entries)
  val threshold = setting("threshold","80").toIntOrNull()?.coerceIn(50,100)?:80
  return if(spent<=0 || spent<=limit*threshold/100) "happy" else if(spent<=limit) "ok" else "sad"
 }
 fun mood(date:LocalDate):String {
  val entries=txns()
  val value=entries.filter{it.status!="已忽略"&&day(it.time)==date}.sumOf(::netExpense)
  return if(date==LocalDate.now())computedMood(date,value,entries) else calendarMood(date,value,entries)
 }
 fun calendarMood(date:LocalDate,spent:Long,entries:List<Txn>):String =
  setting("mood:$date").ifBlank { setting("finalMood:$date").ifBlank { computedMood(date,spent,entries) } }

 @Synchronized fun takeDaySnapshot() {
  if(setting("onboarded")!="true")return
  val today=LocalDate.now()
  val last=setting("snapshotDate")
  if(last==today.toString())return
  val entries=txns()
  val byDay=entries.filter{it.status!="已忽略"}.groupBy {day(it.time)}
  val yesterday=today.minusDays(1)
  val start=runCatching{LocalDate.parse(last)}.getOrDefault(yesterday).coerceAtLeast(today.minusDays(366))
  val updates=linkedMapOf<String,String>()
  var date=start
  while(date<=yesterday){
   if(setting("finalMood:$date").isBlank())updates["finalMood:$date"]=computedMood(date,byDay[date].orEmpty().sumOf(::netExpense),entries)
   date=date.plusDays(1)
  }
  if(setting("quota:$today").isBlank()) {
   val basis = calculateBudgetBasis(today, entries)
   updates["quota:$today"] = (basis?.daily ?: 0L).toString()
  }
  updates["snapshotDate"]=today.toString()
  prefs=runBlocking{ctx.settingsStore.edit{ values->updates.forEach{(key,value)->values[stringPreferencesKey(key)]=value} }}
  changed()
 }

 fun linkRefund(refund:Txn,entries:List<Txn> = txns()){
  if(refund.direction!="退款" || refund.merchant=="待确认商户"){refund.refundOf="";return}
  val already=entries.filter { it.direction=="退款" && it.status!="已忽略" && it.id!=refund.id }
    .map { it.refundOf }.toSet()
  val original=entries.singleOrNull {
   it.direction=="支出" && it.status!="已忽略" && it.cents==refund.cents && it.id !in already &&
      it.merchant.equals(refund.merchant,true) && it.time<=refund.time && day(it.time)>=day(refund.time).minusDays(30)
  }
  refund.refundOf=original?.id.orEmpty()
 }
 fun split(t:Txn){val ids=t.events.toList();for(i in ids.indices)for(j in i+1 until ids.size)put("NoMerge","nomerge:${ids[i]}:${ids[j]}",JSONObject().put("a",ids[i]).put("b",ids[j]));t.events=mutableListOf(ids.first());t.status="待确认";save(t);ids.drop(1).forEach{save(t.copy(id=UUID.randomUUID().toString(),events=mutableListOf(it),status="待确认"))}}
 fun backup():String=JSONObject().put("format","kongye-ledger-v1").put("records",JSONArray(db.records().all("Txn").plus(db.records().all("RawEvent")).plus(db.records().all("Rule")).plus(db.records().all("NoMerge")).map {JSONObject().put("id",it.id).put("kind",it.kind).put("payload",JSONObject(it.payload))})).put("settings",JSONObject(prefs.asMap().mapKeys{it.key.name})).toString(2)
 @Synchronized fun restore(text: String) {
  val backup = JSONObject(text)
  require(backup.getString("format") == "kongye-ledger-v1") { "备份格式不正确" }
  val records = backup.getJSONArray("records")
  val ids = mutableSetOf<String>()
  val prepared = List(records.length()) { index ->
   val item = records.getJSONObject(index)
   val id = item.getString("id")
   val kind = item.getString("kind")
   val payload = item.getJSONObject("payload")
   require(id.isNotBlank() && ids.add(id) && kind in setOf("Txn", "RawEvent", "Rule", "NoMerge")) { "备份记录无效" }
   if (kind == "Txn") require(Txn.from(payload).id == id) { "账单编号不一致" }
   LedgerDb.Record().apply { this.id = id; this.kind = kind; this.payload = payload.toString() }
  }
  val settings = mutableMapOf<String, String>()
  if (backup.has("settings")) {
   val values = backup.getJSONObject("settings")
   values.keys().forEach { key ->
    val value = values.get(key)
    require(key.isNotBlank() && value is String) { "备份设置无效" }
    settings[key] = value
   }
  }
  db.runInTransaction { prepared.forEach { db.records().put(it) } }
  if (settings.isNotEmpty()) {
   prefs = runBlocking {
    ctx.settingsStore.edit { values ->
     settings.forEach { (key, value) -> values[stringPreferencesKey(key)] = value }
    }
   }
  }
  changed()
 }
 companion object { @Volatile private var instance:Store?=null;fun get(c:Context)=instance?:synchronized(this){instance?:Store(c.applicationContext).also{instance=it}} }
}
