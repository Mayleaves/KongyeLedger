package com.kongye.ledger

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

internal val tailPattern = Regex("""(?:尾号|尾数|末四位|\*{2,})[（(\s:：]*(\d{4})(?!\d)""")
private val txTime = Regex("""(?<!\d)(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
private val serialPattern = Regex("""(?:交易单号|交易订单号|订单号)[：:\s]+([0-9A-Za-z_-]{6,80})""")
private val nonPayment = Regex("验证码|支付失败|付款失败|交易失败|支付取消|付款取消|退款失败|待支付|待付款")
private val income = Regex("收款到账|收款成功|收款金额|到账金额|收入|入账|存入")
private val outcomeWord = Regex("支出|扣款|消费|付款|支付|实付")
private val unrelatedAmount = Regex("(?:可用余额|账户余额|余额|优惠|手续费|订单原价|原价|原金额|优惠券)\\s*[：:为]?\\s*(?:人民币|RMB)?\\s*$", RegexOption.IGNORE_CASE)
private val paymentNumber = """((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]{1,2})?)(?![0-9.]|,[0-9])"""
private val shoppingAmount = Regex(
    """(?:实付(?:款项|款)?(?:金额)?|支付金额|付款金额|交易金额)[：:为\s]*(?:人民币|RMB)?\s*[¥￥]?\s*$paymentNumber"""
)
private val shoppingCurrency = Regex("""(?:[¥￥]\s*$paymentNumber|(?<![0-9.,])$paymentNumber\s*元)""")
private val shoppingMerchant = Regex("""(?:店铺名称|店铺|卖家|商家)[：: ]+([^，,。；;\n]{1,48})""")
private val orderWithCurrency = Regex("""(?:运费|邮费|折扣|省下|减免|优惠|原价|退款|余额|总价|合计|红包|补贴|手续费|优惠券)[：:为\s]*(?:人民币|RMB)?\s*[¥￥]?\s*$""")

internal data class ParseResult(val transaction: Txn?, val reason: String, val confidence: Int = 0)

internal class LedgerParser(text: String) {
    private data class Template(val source: String, val senders: Set<String>, val keywords: List<String>, val amount: Regex, val merchant: Regex)
    private val templates: List<Template>
    init {
        require(text.length <= 65536) { "规则文件不能超过 64 KB" }
        val items = JSONArray(text)
        require(items.length() in 1..32) { "规则须包含 1 到 32 个模板" }
        templates = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val source = item.getString("source")
            require(source == "notification" || source == "sms") { "来源须为 notification 或 sms" }
            fun strings(name: String): List<String> {
                val values = item.optJSONArray(name) ?: return emptyList()
                require(values.length() <= 60)
                return (0 until values.length()).map { values.getString(it).trim() }.filter { it.isNotEmpty() }
            }
            fun expression(name: String): Regex {
                val regex = item.getString(name)
                require(regex.length in 1..700) { "正则长度无效" }
                return Regex(regex)
            }
            Template(source, strings("senders").toSet(), strings("keywords"), expression("amount"), expression("merchant"))
        }
    }

    fun parse(raw: JSONObject): ParseResult {
        val lines = listOf("body", "title", "text", "subText").map { raw.optString(it).trim() }
            .filter(String::isNotBlank).distinct()
        val text = lines.joinToString("\n").take(2000)
        if (nonPayment.containsMatchIn(text)) return ParseResult(null, "验证码或交易未完成")
        val sender = raw.optString("sender")
        val shoppingApp = shoppingPaymentApps[sender]?.takeIf { raw.optString("source") == "notification" }
        if (shoppingApp != null && !isShoppingPayment(text)) return ParseResult(null, "订单尚未确认付款")
        val eligible = templates.filter {
            it.source == raw.optString("source") && (it.senders.isEmpty() || sender in it.senders) &&
                (it.keywords.isEmpty() || it.keywords.any(text::contains))
        }
        val template = eligible.firstOrNull { it.senders.isNotEmpty() } ?: eligible.firstOrNull()
        if (template == null && shoppingApp == null) return ParseResult(null, "没有匹配的来源模板或交易关键词")
        fun amounts(pattern: Regex): List<Long> = pattern.findAll(text).mapNotNull { match ->
            val group = match.groups.getOrNullForAmount()
                ?: (if (shoppingApp != null && pattern == shoppingCurrency) match.groups[2] else null)
                ?: return@mapNotNull null
            val lead = text.substring(0, group.range.first).takeLast(24)
            if (unrelatedAmount.containsMatchIn(lead.trimEnd('¥', '￥', ' '))) return@mapNotNull null
            if (shoppingApp != null && orderWithCurrency.containsMatchIn(lead)) return@mapNotNull null
            runCatching { cents(group.value) }.getOrNull()?.takeIf { it > 0 }
        }.distinct().take(2).toList()
        val matches = if (shoppingApp != null) {
            amounts(shoppingAmount).ifEmpty {
                if (template != null && template.senders.isNotEmpty()) amounts(template.amount)
                else emptyList()
            }.ifEmpty { amounts(shoppingCurrency) }
        } else amounts(requireNotNull(template).amount)
        if (matches.isEmpty()) return ParseResult(null, "找不到明确的交易金额")
        if (matches.size > 1) return ParseResult(null, "出现多个交易金额，请核对原文")
        val namedMerchant = (template?.merchant?.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: shoppingMerchant.find(text)?.groupValues?.get(1)?.takeIf { shoppingApp != null })?.trim().orEmpty()
        val merchant = namedMerchant.ifBlank { shoppingApp ?: "待确认商户" }
        val merchantUnknown = merchant == "待确认商户" || merchant == shoppingApp && namedMerchant.isBlank()
        val body = raw.optString("body").ifBlank { raw.optString("text") }.ifBlank { text }
        val direction = when {
            shoppingApp != null -> "支出"
            text.contains("退款") -> "退款"
            text.contains("转账") || text.contains("红包") -> "转账"
            income.containsMatchIn(body) || body.contains("收款") || body.contains("到账") && !outcomeWord.containsMatchIn(body) -> "收入"
            outcomeWord.containsMatchIn(body) -> "支出"
            income.containsMatchIn(text) || text.contains("收款") || text.contains("到账") -> "收入"
            outcomeWord.containsMatchIn(text) -> "支出"
            else -> ""
        }
        if (direction.isEmpty()) return ParseResult(null, "无法判断交易方向")
        val timestamp = txTime.find(text)?.groupValues?.let { values ->
            runCatching {
                LocalDateTime.of(values[1].toInt(), values[2].toInt(), values[3].toInt(),
                    values[4].toInt(), values[5].toInt(), values[6].toIntOrNull() ?: 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: raw.getLong("time")
        val account = tailPattern.find(text)?.groupValues?.get(1).orEmpty()
        val serial = serialPattern.find(text)?.groupValues?.get(1).orEmpty()
        return ParseResult(
            Txn(cents = matches.single(), merchant = merchant,
                category = when (shoppingApp) { "美团" -> "餐饮"; null -> "其他"; else -> "购物" }, direction = direction,
                time = timestamp, account = account, serial = serial, source = "自动",
                events = mutableListOf(raw.getString("id"))),
            if (merchantUnknown) "缺少商户" else "识别完成",
            if (merchantUnknown) 55 else 90
        )
    }
}

private fun MatchGroupCollection.getOrNullForAmount(): MatchGroup? =
    if (size > 1) get(1) else null
