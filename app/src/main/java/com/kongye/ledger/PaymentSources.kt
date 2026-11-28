package com.kongye.ledger

internal val paymentNotificationChannels = mapOf(
    "wechat" to "com.tencent.mm",
    "alipay" to "com.eg.android.AlipayGphone",
    "unionpay" to "com.unionpay",
    "douyin" to "com.ss.android.ugc.aweme",
    "xianyu" to "com.taobao.idlefish",
    "alibaba" to "com.alibaba.wireless",
    "meituan" to "com.sankuai.meituan"
)

internal val shoppingPaymentApps = mapOf(
    paymentNotificationChannels.getValue("douyin") to "抖音",
    paymentNotificationChannels.getValue("xianyu") to "闲鱼",
    paymentNotificationChannels.getValue("alibaba") to "阿里巴巴",
    paymentNotificationChannels.getValue("meituan") to "美团"
)

private val completedOrderPayment = Regex(
    """(?:支付|付款)已?(?:成功|完成)(?!后|即|可|将|有机会)|(?:已|完成)(?:成功)?(?:支付|付款)(?!后|即|可)"""
)
private val unpaidOrAdvertisement = Regex(
    """验证码|待付款|待支付|未付款|未支付|未完成(?:付款|支付)|(?:支付|付款)(?:失败|取消)|(?:完成|成功)(?:支付|付款)后|(?:即将|请|提醒您)(?:完成)?(?:支付|付款)|退款成功|已退款|退款到账|抽奖|限时抢购"""
)

internal fun isShoppingPayment(text: String): Boolean =
    completedOrderPayment.containsMatchIn(text) && !unpaidOrAdvertisement.containsMatchIn(text)
