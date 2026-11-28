package com.kongye.ledger

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class ScreenPayment(val merchant: String, val cents: Long, val time: Long) {
    fun json(): JSONObject = JSONObject().put("merchant", merchant).put("cents", cents).put("time", time)
}

internal object ScreenPaymentParser {
    private val amount = Regex("""[¥￥Y]\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\.[0-9]{2})(?!\d)""")
    private val cardAmount = Regex("""^(?:[¥￥Y]\s*)?((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\.[0-9]{2})$""")
    private val clock = Regex("""^(?:今天\s*)?([0-2]?\d):([0-5]\d)(?::([0-5]\d))?$""")
    private val completed = Regex("""^(?:支付成功|付款成功|交易成功|已支付|已付款)$""")
    private val walletPaid = Regex("""^使用(?:零钱通?|银行卡|信用卡|储蓄卡|余额|花呗)(?:支付|付款)$""")
    private val merchantField = Regex("""^(?:商户(?:名称)?|收款方|交易对方|商品|商家)[：:\s]*(.*)$""")

    fun parse(packageName: String, text: String, now: Long): ScreenPayment? {
        if (packageName !in paymentNotificationChannels.values) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (packageName == "com.eg.android.AlipayGphone" || packageName == "com.sankuai.meituan" ||
            packageName == "com.tencent.mm" && lines.none { walletPaid.matches(it) }) {
            return parseCheckout(packageName, lines, now)
        }
        val anchors = lines.indices.filter { completed.matches(lines[it]) || walletPaid.matches(lines[it]) }
        if (anchors.size != 1) return null
        val anchor = anchors.single()
        val card = walletPaid.matches(lines[anchor])
        if (card && (packageName != "com.tencent.mm" || lines.none { it == "微信支付" })) return null
        val nearby = lines.subList((anchor - 6).coerceAtLeast(0), (anchor + 12).coerceAtMost(lines.size))
        if (nearby.any { it.contains("待付款") || it.contains("支付失败") || it.contains("退款成功") }) return null
        val values = if (card) {
            lines.drop(anchor + 1).take(3).mapNotNull { cardAmount.matchEntire(it)?.groupValues?.get(1) }
                .mapNotNull { runCatching { cents(it) }.getOrNull()?.takeIf { n -> n > 0 } }.distinct()
        } else amount.findAll(lines.drop(anchor + 1).take(8).joinToString("\n"))
            .mapNotNull { runCatching { cents(it.groupValues[1]) }.getOrNull()?.takeIf { n -> n > 0 } }.distinct().toList()
        if (values.size != 1) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val stamp = if (card) {
            val preceding = lines.take(anchor)
            if (preceding.any { Regex("""^(?:昨天|前天)(?:\s+\d{1,2}:\d{2})?$""").matches(it) ||
                    Regex("""^20\d{2}[年/.-]\d{1,2}[月/.-]\d{1,2}日?(?:\s+\d{1,2}:\d{2})?$""").matches(it) }) return null
            val index = preceding.indexOfLast { clock.matches(it) }
            if (index < 0) return null
            val parts = clock.matchEntire(preceding[index])!!.groupValues
            val time = runCatching { LocalTime.of(parts[1].toInt(), parts[2].toInt(), parts[3].toIntOrNull() ?: 0) }.getOrNull() ?: return null
            today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        } else now
        if (stamp > now + 60_000) return null
        val merchant = if (card) {
            lines.take(anchor).lastOrNull { line ->
                !clock.matches(line) && line != "微信支付" && line.length in 2..48 &&
                    !line.contains("详情") && !line.contains("通知") && !line.contains("优惠") &&
                    !line.contains("元") && !line.contains("备注") && !line.contains("有效期") &&
                    !line.contains("新消息") && !line.contains("兑换")
            }
        } else {
            nearby.indices.firstNotNullOfOrNull { index ->
                merchantField.matchEntire(nearby[index])?.let { match ->
                    match.groupValues[1].ifBlank { nearby.getOrNull(index + 1).orEmpty() }.takeIf { it.length in 2..48 }
                }
            }
        }
        if (merchant.isNullOrBlank()) return null
        // Small brand marks can be read together with the adjacent merchant label.
        val knownBrand = listOf("美团", "京东", "淘宝", "拼多多").firstOrNull {
            merchant.startsWith(it) && merchant.length <= it.length * 2 + 1
        }
        return ScreenPayment(knownBrand ?: merchant, values.single(), stamp)
    }

    // Qualifiers such as “极速” may have OCR errors; keep the completion phrase exact.
    private val checkoutComplete = Regex("""^[\p{IsHan}]{0,4}(?:支付|付款|交易|扣款)(?:成功|完成)$|^已(?:支付|付款)$""")
    private val checkoutIncomplete = Regex("""未|不|尚|无|请|待|非|后|若|如果|失败|取消|关闭|退款|收入|收款到账""")
    private val checkoutPrice = Regex("""^(?:实付(?:金额)?|支付金额|付款金额|订单实付)?[：:\s]*([-−]?)\s*[¥￥]?\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)\s*(元)?$""")
    private val checkoutMerchant = Regex("""^(?:商户(?:名称|全称)?|收款(?:方|商家|商户)|交易对方|商家|商品说明)[：:\s]*(.*)$""")
    private val checkoutTime = Regex("""(?<!\d)(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val otherPrice = Regex("""余额|优惠|原价|优惠券|立减|减免|运费|邮费|手续费|补贴""")

    private fun parseCheckout(packageName: String, lines: List<String>, now: Long): ScreenPayment? {
        val anchors = lines.indices.filter { checkoutComplete.matches(lines[it]) && !checkoutIncomplete.containsMatchIn(lines[it]) }
        if (anchors.size != 1) return null
        val anchor = anchors.single()
        val nearby = lines.subList((anchor - 8).coerceAtLeast(0), (anchor + 14).coerceAtMost(lines.size))
        if (nearby.any { Regex("""待付款|待支付|未付款|支付失败|付款失败|交易关闭|退款成功|退款中|收入|收款到账""").containsMatchIn(it) }) return null
        val prices = nearby.indices.mapNotNull { index ->
            val line = nearby[index]
            if (otherPrice.containsMatchIn(line) || otherPrice.containsMatchIn(nearby.getOrNull(index - 1).orEmpty())) return@mapNotNull null
            val match = checkoutPrice.matchEntire(line) ?: return@mapNotNull null
            val hasUnit = line.contains('¥') || line.contains('￥') || line.endsWith('元') ||
                line.startsWith("实付") || line.startsWith("支付金额") || line.startsWith("付款金额") || line.startsWith("订单实付")
            if (!hasUnit && !match.groupValues[2].contains('.')) return@mapNotNull null
            runCatching { cents(match.groupValues[2]) }.getOrNull()?.takeIf { it > 0 }?.let { index to it }
        }
        if (prices.map { it.second }.distinct().size != 1) return null
        val amountIndex = prices.first().first
        val headingMerchant = nearby.take(amountIndex).lastOrNull {
            it.length in 2..64 && !checkoutComplete.matches(it) && !checkoutPrice.matches(it) &&
                !Regex("""^(?:实付(?:金额)?|支付金额|付款金额|交易金额|订单实付|人民币|商家|商户(?:名称|全称)?|收款方|交易对方|当前状态|支付状态)$""").matches(it) &&
                !otherPrice.containsMatchIn(it) && !Regex("""详情|账单|支付结果|收支明细|支付宝|美团支付|完成|时间|单号|付款方式|交易状态|VPN|^\d[\d\s:./-]*$""").containsMatchIn(it)
        }
        val merchant = headingMerchant ?: nearby.indices.firstNotNullOfOrNull { index ->
            checkoutMerchant.matchEntire(nearby[index])?.let { match ->
                val value = match.groupValues[1].ifBlank { nearby.getOrNull(index + 1).orEmpty() }
                value.takeIf { it.length in 2..64 && !checkoutPrice.matches(it) && !checkoutComplete.matches(it) &&
                    !checkoutMerchant.matches(it) && !it.contains("时间") && !it.contains("单号") }
            }
        } ?: if (packageName == "com.sankuai.meituan") "美团" else return null
        val times = checkoutTime.findAll(lines.joinToString("\n")).map { it.groupValues }.toList()
        if (times.size > 1) return null
        val stamp = times.singleOrNull()?.let { parts ->
            runCatching {
                java.time.LocalDateTime.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(),
                    parts[4].toInt(), parts[5].toInt(), parts[6].toIntOrNull() ?: 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return null
        } ?: if (lines.any { it.contains("交易时间") || it.contains("付款时间") || it.contains("支付时间") || it.contains("下单时间") || it.contains("创建时间") }) return null else now
        if (stamp > now + 60_000) return null
        return ScreenPayment(merchant, prices.first().second, stamp)
    }

    fun transaction(raw: JSONObject): ParseResult {
        val value = runCatching { JSONObject(raw.getString("body")) }.getOrNull()
            ?: return ParseResult(null, "无效的屏幕识别结果")
        val merchant = value.optString("merchant")
        val amount = value.optLong("cents")
        if (merchant.isBlank() || amount <= 0) return ParseResult(null, "缺少明确的商户或金额")
        return ParseResult(Txn(cents = amount, merchant = merchant, category = "其他", direction = "支出",
            time = value.getLong("time"), source = "自动", events = mutableListOf(raw.getString("id"))),
            "屏幕识别，请核对", 70)
    }
}
