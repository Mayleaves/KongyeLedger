package com.kongye.ledger
import org.w3c.dom.*
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.time.*
import java.time.format.DateTimeFormatter
object BillImport {
 // Bounded equivalent of readNBytes, also available on Android 8–12.
 private fun InputStream.readBounded(limit:Int):ByteArray {
  val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
  while(output.size()<limit){
   val count=read(buffer,0,minOf(buffer.size,limit-output.size()))
   if(count<0)break
   if(count==0){val next=read();if(next<0)break;output.write(next)}else output.write(buffer,0,count)
  }
  return output.toByteArray()
 }
 fun csv(text:String):List<List<String>>{val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder();var quote=false;var i=0;while(i<text.length){val c=text[i];when{c=='"'&&quote&&i+1<text.length&&text[i+1]=='"'->{cell.append('"');i++};c=='"'->quote=!quote;c==','&&!quote->{row.add(cell.toString());cell.clear()};c=='\n'&&!quote->{row.add(cell.toString().trimEnd('\r'));rows.add(row);row=mutableListOf();cell.clear()};else->cell.append(c)};i++};row.add(cell.toString());if(row.any{it.isNotBlank()})rows.add(row);return rows}
 fun xml(bytes:ByteArray):Document {val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))}
 fun xlsx(bytes:ByteArray):List<List<String>> {val files=mutableMapOf<String,ByteArray>();ZipInputStream(ByteArrayInputStream(bytes)).use{z->var total=0;while(true){val e=z.nextEntry?:break;if(!e.isDirectory){val b=z.readBounded(20*1024*1024+1);total+=b.size;require(b.size<=20*1024*1024&&total<=40*1024*1024){"账单文件过大"};files[e.name]=b}}};val shared=files["xl/sharedStrings.xml"]?.let{b->val n=xml(b).getElementsByTagName("si");(0 until n.length).map{n.item(it).textContent}}?:emptyList();val sheet=files.keys.filter{it.startsWith("xl/worksheets/sheet")&&it.endsWith(".xml")}.sorted().firstOrNull()?:error("未找到工作表");val rows=xml(files.getValue(sheet)).getElementsByTagName("row");return (0 until rows.length).map{r->val cells=(rows.item(r) as Element).getElementsByTagName("c");val values=mutableListOf<String>();for(i in 0 until cells.length){val cell=cells.item(i) as Element;val letters=cell.getAttribute("r").takeWhile{it.isLetter()};val col=letters.fold(0){a,c->a*26+(c-'A'+1)}-1;while(values.size<=col)values.add("");val v=cell.getElementsByTagName("v").item(0)?.textContent?:cell.textContent;values[col]=if(cell.getAttribute("t")=="s")shared.getOrElse(v.toInt()){ "" } else v};values}}
 fun run(s:Store,bytes:ByteArray):String {
  require(bytes.size<40*1024*1024){"文件过大"};val rows=if(bytes.take(2)==listOf(80.toByte(),75.toByte()))xlsx(bytes) else {val utf=bytes.toString(Charsets.UTF_8);csv(if(utf.contains('\uFFFD'))bytes.toString(Charset.forName("GBK"))else utf)}
  val hi=rows.indexOfFirst{r->r.any{it.contains("交易时间")||it.contains("付款时间")}&&r.any{it.contains("金额")}};require(hi>=0){"未找到微信或支付宝账单表头"};val header=rows[hi].map{it.trim().trim('\uFEFF')}
  fun index(vararg names:String)=header.indexOfFirst{h->names.any{h==it||h.startsWith(it)}}
  val ti=index("交易时间","付款时间");val ai=index("金额");val mi=index("交易对方","商品名称","商品");val di=index("收/支","收支");val si=index("交易单号","交易订单号");val status=index("交易状态","当前状态")
  var added=0;var matched=0;var skipped=0;val conflicts=mutableListOf<String>()
  for(r in rows.drop(hi+1)){fun col(i:Int)=r.getOrElse(i){""}.trim()
   if(col(ai).isBlank())continue
   if(status>=0&&Regex("关闭|失败|撤销").containsMatchIn(col(status))){skipped++;continue}
   val amount=runCatching{cents(col(ai))}.getOrNull()?:continue
   val time=runCatching{LocalDateTime.parse(col(ti).take(19),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}.getOrNull()?:continue
   val merchant=col(mi).ifBlank{"账单补录"};val serial=col(si).trim('`');val type=index("交易类型","交易分类")
   val direction=when {
    col(type).contains("退款") || col(index("商品说明","商品名称")).startsWith("退款")->"退款"
    col(di).contains("收入")->"收入"
    col(di).contains("不计")||col(di).contains("中性")||col(type).contains("转账")->"转账"
    else->"支出"
   }
   val current=s.txns()
   val serialMatch=current.firstOrNull{serial.isNotBlank()&&it.serial==serial&&it.direction==direction}
   val candidates=if(serialMatch!=null)emptyList() else current.filter {
    it.status!="已忽略" && it.cents==amount && it.direction==direction && kotlin.math.abs(it.time-time)<=300000 &&
        (serial.isBlank() || it.serial.isBlank()) && merchant!="账单补录" &&
        (merchant.contains(it.merchant,true)||it.merchant.contains(merchant,true))
   }
   val old=serialMatch?:candidates.singleOrNull()
   if(candidates.size>1)conflicts.add("$merchant：发现多笔相近账目，补录后请核对。")
   if(old!=null){if(old.cents!=amount){conflicts.add("$merchant：账本 ¥${money(old.cents)} / 账单 ¥${money(amount)}");continue};if(old.source=="手动"){old.status="待确认";old.note="账单发现相同交易，请确认是否合并。单号 $serial";s.save(old);conflicts.add("$merchant：与手动记录重复，待确认");continue};if(serial.isNotBlank())old.serial=serial;if(merchant.length>old.merchant.length)old.merchant=merchant;s.save(old);matched++;continue}
   s.save(Txn(cents=amount,merchant=merchant,category=if(direction in listOf("收入","退款"))"收入" else s.classify(merchant),direction=direction,time=time,source="补录",serial=serial,status=if(direction=="转账"||candidates.size>1)"待确认" else "已入账").also { s.linkRefund(it,current) });added++
  }
  return "对账完成\n自动已记 $matched 笔\n补录 $added 笔\n需核对 ${conflicts.size} 笔\n跳过失败交易 $skipped 笔\n"+conflicts.joinToString("\n")
 }
}