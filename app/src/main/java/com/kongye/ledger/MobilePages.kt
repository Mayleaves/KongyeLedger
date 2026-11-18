package com.kongye.ledger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun ledgerPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun LedgerNav(page: String, go: (String) -> Unit) {
    val theme = LocalLedgerTheme.current
    val items = listOf(
        Triple("首页", "home", false),
        Triple("流水", "list", false),
        Triple("记一笔", "plus", true),
        Triple("统计", "chart", false),
        Triple("我的", "gear", false)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.canvas)
            .border(BorderStroke(2.dp, Ink.copy(alpha = 0.12f)))
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, icon, isPrimary) ->
            val selected = page == label || (label == "统计" && page == "月报")
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val shift by animateDpAsState(
                if (pressed) 2.dp else 0.dp,
                spring(dampingRatio = 0.55f, stiffness = 850f),
                label = "nav_shift"
            )
            val jellyScale by animateFloatAsState(
                targetValue = if (pressed) 0.92f else if (selected) 1.03f else 1f,
                animationSpec = spring(dampingRatio = 0.50f, stiffness = 720f),
                label = "nav_scale"
            )
            val bgColor by animateColorAsState(
                targetValue = when {
                    isPrimary -> theme.primary
                    selected -> theme.primary
                    else -> Color.White
                },
                animationSpec = tween(160),
                label = "nav_bg"
            )
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .weight(if (isPrimary) 1.15f else 1f)
                    .height(52.dp)
                    .scale(jellyScale)
                    .padding(end = 2.dp, bottom = 2.dp)
            ) {
                Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, shape))
                Column(
                    Modifier
                        .matchParentSize()
                        .offset(shift, shift)
                        .background(bgColor, shape)
                        .border(2.2.dp, Ink, shape)
                        .clip(shape)
                        .clickable(interactionSource = interaction, indication = null) { go(label) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AppIcon(icon, Modifier.size(if (isPrimary) 20.dp else 17.dp))
                    Spacer(Modifier.height(1.dp))
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        color = Ink,
                        fontSize = 11.sp,
                        fontWeight = if (selected || isPrimary) FontWeight.Black else FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(first: Boolean, next: () -> Unit, skip: () -> Unit) {
    val fg = if (first) Color.White else Ink
    val currentNext by rememberUpdatedState(next)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = (maxHeight * .44f).coerceIn(200.dp, 320.dp)
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(first) {
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = {
                            if (distance < -56.dp.toPx()) currentNext()
                        }
                    ) { change, delta -> change.consume(); distance += delta }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            if (first) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.65f),
                            RoundedCornerShape(50)
                        )
                        .border(1.8.dp, if (first) Color.White.copy(alpha = 0.5f) else Ink, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Kongye · 空叶", color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = skip) {
                    Text("稍后设置", color = fg, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (first) "Welcome" else "Now..",
                    color = if (first) Yellow else Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Serif
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (first) "欢迎来到\n空叶记账" else "付钱的那一刻\n噗噗来记账",
                    color = fg,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                    lineHeight = 40.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (first) "我是噗噗，一只会自己记账的小猪罐。\n每一笔花销，都有噗噗陪着你。\n账本离线保存在你的手机里。"
                    else "微信、支付宝、银行短信，噗噗都看得懂。\n按需开启付款通知和银行短信，识别不准的账目由你确认。",
                    color = fg.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        if (first) Color(0xff343e5e) else Color(0xffef947c),
                        RoundedCornerShape(28.dp)
                    )
                    .border(2.5.dp, Ink, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    if (first) {
                        val starPositions = listOf(
                            Offset(w * 0.14f, h * 0.16f) to 4.dp.toPx(),
                            Offset(w * 0.78f, h * 0.12f) to 3.dp.toPx(),
                            Offset(w * 0.68f, h * 0.28f) to 2.5.dp.toPx(),
                            Offset(w * 0.22f, h * 0.36f) to 3.dp.toPx()
                        )
                        starPositions.forEach { (pos, r) -> drawCircle(Yellow, radius = r, center = pos) }
                        drawCircle(Yellow, radius = 26.dp.toPx(), center = Offset(w * 0.80f, h * 0.22f))
                        drawCircle(Color(0xff343e5e), radius = 21.dp.toPx(), center = Offset(w * 0.75f, h * 0.19f))
                        val hill1 = Path().apply {
                            moveTo(0f, h * 0.78f)
                            quadraticTo(w * 0.3f, h * 0.64f, w * 0.55f, h * 0.75f)
                            quadraticTo(w * 0.8f, h * 0.84f, w, h * 0.72f)
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(hill1, Color(0xff56628a))
                        val hill2 = Path().apply {
                            moveTo(0f, h * 0.86f)
                            quadraticTo(w * 0.35f, h * 0.76f, w * 0.68f, h * 0.85f)
                            quadraticTo(w * 0.88f, h * 0.90f, w, h * 0.82f)
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(hill2, Color(0xff2a3250))
                    } else {
                        val hill = Path().apply {
                            moveTo(0f, h * 0.80f)
                            quadraticTo(w * 0.32f, h * 0.68f, w * 0.64f, h * 0.78f)
                            quadraticTo(w * 0.85f, h * 0.84f, w, h * 0.74f)
                            lineTo(w, h)
                            lineTo(0f, h)
                            close()
                        }
                        drawPath(hill, Color(0xffe58167))
                        val trail = Path().apply {
                            moveTo(w * 0.38f, h * 0.28f)
                            quadraticTo(w * 0.56f, h * 0.36f, w * 0.54f, h * 0.52f)
                        }
                        drawPath(
                            trail,
                            Color.White,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                            )
                        )
                    }
                    val cloudY = h * 0.79f
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.18f, cloudY),
                        size = Size(w * 0.64f, h * 0.16f),
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    )
                    drawCircle(Color.White, radius = 26.dp.toPx(), center = Offset(w * 0.34f, cloudY))
                    drawCircle(Color.White, radius = 32.dp.toPx(), center = Offset(w * 0.50f, cloudY - 6.dp.toPx()))
                    drawCircle(Color.White, radius = 25.dp.toPx(), center = Offset(w * 0.66f, cloudY + 2.dp.toPx()))
                }

                if (!first) {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 18.dp, top = 14.dp)
                            .rotate(-5f)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .border(2.5.dp, Ink, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Color(0xff5cc46a), RoundedCornerShape(8.dp))
                                .border(2.dp, Ink, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("微", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                        Column {
                            Text("微信支付", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("已支付 ¥15.90", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                CoinBadge(
                    Modifier
                        .align(if (first) Alignment.CenterStart else Alignment.TopCenter)
                        .offset(
                            x = if (first) 38.dp else 44.dp,
                            y = if (first) 10.dp else 68.dp
                        )
                        .size(36.dp)
                )

                AnimatedPupu(
                    state = "happy",
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(stageHeight * 0.74f)
                        .padding(bottom = 10.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Pill(
                text = "下一步 →",
                color = Yellow,
                modifier = Modifier.fillMaxWidth(),
                onClick = next
            )

            Spacer(Modifier.height(14.dp))

            OnboardingDots(activeIndex = if (first) 0 else 1, light = first)
        }
    }
}

@Composable
fun OnboardingDots(activeIndex: Int, light: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { idx ->
            val isActive = idx == activeIndex
            val width by animateDpAsState(
                if (isActive) 22.dp else 8.dp,
                spring(dampingRatio = 0.65f, stiffness = 600f),
                label = "dot_w"
            )
            val color = if (light) {
                if (isActive) Yellow else Color.White.copy(alpha = 0.45f)
            } else {
                if (isActive) Ink else Ink.copy(alpha = 0.35f)
            }
            Box(
                Modifier
                    .size(width, 8.dp)
                    .background(color, RoundedCornerShape(50))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(s: Store, notify: () -> Unit, sms: () -> Unit, battery: () -> Unit, finish: () -> Unit) {
    var budget by rememberSaveable { mutableStateOf(money(s.setting("budget", "300000").toLongOrNull() ?: 300000)) }
    var error by remember { mutableStateOf(false) }
    ScrollPage {
        Text(
            "And..",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif
        )
        Text("让噗噗开始值班", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
        Text(
            "给噗噗开三扇门，它才能自己记账。按需授权，通知与银行动账短信仅在手机本地处理。",
            lineHeight = 22.sp,
            color = Ink.copy(alpha = 0.85f),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium
        )
        PermissionCards(s, notify, sms, battery)

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                .border(2.dp, Ink, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppIcon("lock", Modifier.size(18.dp))
            Text(
                "账目只存在这台手机里，不联网、不上传、不用注册",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink
            )
        }

        HardCard {
            Text("设定每月总预算", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("总预算将自动换算为每日额度，作为噗噗心情与日历盖章的基准。", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("2000", "3000", "5000", "8000").forEach { preset ->
                    Pill("¥$preset", if (budget == preset || budget == "$preset.00") Yellow else Color.White) {
                        budget = preset
                        error = false
                    }
                }
            }
            Field(budget, "每月预算（元）", true) { budget = it; error = false }
            if (error) Text("请输入大于 0 的有效金额", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OnboardingDots(activeIndex = 2, light = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = finish) {
                    Text("用默认预算，稍后设置", color = Ink, fontWeight = FontWeight.Bold)
                }
                Pill("开始记账") {
                    val amount = runCatching { cents(budget) }.getOrNull()
                    if (amount == null || amount <= 0) error = true
                    else { s.set("budget", amount.toString()); finish() }
                }
            }
        }
    }
}

@Composable
fun AuthorizationCards(s: Store, notification: () -> Unit, sms: () -> Unit, battery: () -> Unit) {
    val context = LocalContext.current
    val revision by s.revision.collectAsState()
    val states = remember(revision, context) {
        val enabled = notificationAccess(context)
        val smsAllowed = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        listOf(enabled, smsAllowed, unrestricted)
    }
    val items = listOf(
        Triple("通知使用权", "看懂微信、支付宝、云闪付的付款通知", notification),
        Triple("银行短信权限", "只读银行白名单号码发来的动账短信", sms),
        Triple("后台运行设置", "允许后台活动与电池不限制，防止漏记", battery)
    )
    items.forEachIndexed { index, (label, desc, open) ->
        val done = states[index]
        HardCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(if (done) Color(0xff7be08f) else Cream, CircleShape)
                        .border(2.2.dp, Ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) AppIcon("check", Modifier.size(16.dp))
                    else Text("${index + 1}", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(
                            if (done) "已开启" else "未开启",
                            color = if (done) GreenIncome else Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(desc, color = Muted, fontSize = 11.5.sp)
                }
                Pill(if (done) "管理" else "去开启", if (done) Color.White else LocalLedgerTheme.current.primary, onClick = open)
            }
        }
    }
}

@Composable
fun HomeMascot(s: Store, txns: List<Txn>, today: LocalDate, left: Long, spent: Long, select: (String) -> Unit) {
    val state = s.mood(today)
    val sums = remember(txns, today) {
        txns.asSequence().filter { day(it.time) == today && it.status != "已忽略" && it.direction == "支出" }
            .groupBy { it.category }.mapValues { (_, entries) -> entries.sumOf { it.cents } }
    }
    val bubbleText = when {
        sums.isEmpty() -> "今天还没有支出记录，好节制！"
        left < 0 -> "今天超了 ¥${money(-left)}，抱抱你"
        state == "ok" -> "还剩 ¥${money(left)}，稳住就能赢！"
        else -> "今天已花 ¥${money(spent)}，表现超棒！"
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SpeechBubble(text = bubbleText)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                listOf("餐饮", "购物").forEach { cat ->
                    CategoryBubble(cat, "¥" + money(sums[cat] ?: 0)) { select(cat) }
                }
            }
            Box(
                Modifier.weight(1.75f),
                contentAlignment = Alignment.Center
            ) {
                AnimatedPupu(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 168.dp, max = 192.dp)
                        .aspectRatio(.94f)
                )
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                listOf("交通", "居住").forEach { cat ->
                    CategoryBubble(cat, "¥" + money(sums[cat] ?: 0)) { select(cat) }
                }
            }
        }
    }
}

fun entryAmount(input: String): Long {
    require(Regex("""\d+(?:\.\d{1,2})?(?:[+−-]\d+(?:\.\d{1,2})?)*""").matches(input))
    val parts = Regex("""[+−-]?\d+(?:\.\d{1,2})?""").findAll(input)
    return parts.fold(0L) { total, match -> Math.addExact(total, cents(match.value.replace('−', '-'))) }
}

private val defaultMerchantsByCategory = mapOf(
    "餐饮" to listOf("瑞幸咖啡", "美团外卖", "饿了么", "早餐", "午餐", "晚餐"),
    "交通" to listOf("地铁", "滴滴出行", "公交", "高铁", "加油"),
    "购物" to listOf("盒马鲜生", "超市", "淘宝", "京东", "日用品"),
    "居住" to listOf("房租", "电费", "水费", "话费宽带", "物业费"),
    "娱乐" to listOf("电影院", "游戏充值", "奶茶甜品", "会员订阅"),
    "医疗" to listOf("药店", "门诊挂号", "体检"),
    "学习" to listOf("买书", "课程资料", "文具"),
    "人情" to listOf("聚餐请客", "红包随礼", "礼物"),
    "收入" to listOf("工资", "奖金", "兼职收入", "报销款", "理财收益"),
    "其他" to listOf("快递费", "日常杂项")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualEntry(
    s: Store,
    finish: () -> Unit,
    initialDate: LocalDate? = null,
    onSavedCelebration: () -> Unit = {}
) {
    val theme = LocalLedgerTheme.current
    val format = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    var expr by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("餐饮") }
    var direction by rememberSaveable { mutableStateOf("支出") }
    var account by rememberSaveable { mutableStateOf("微信") }
    var time by rememberSaveable {
        val baseDate = initialDate ?: LocalDate.now()
        mutableStateOf(LocalDateTime.of(baseDate, LocalTime.now()).format(format))
    }
    var note by rememberSaveable { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf(false) }
    var issues by remember { mutableStateOf("") }
    val preview = remember(expr) { runCatching { entryAmount(expr) }.getOrNull() }
    val allChoices = remember { s.categoryChoices() }

    val infinite = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor_blink"
    )

    val suggestedMerchants = remember(category) {
        val history = s.txns()
            .asSequence()
            .filter { it.category == category && it.merchant.isNotBlank() && it.merchant != category && it.merchant != "待确认商户" }
            .map { it.merchant }
            .distinct()
            .take(4)
            .toList()
        val presets = defaultMerchantsByCategory[category].orEmpty()
        (history + presets).distinct().take(6)
    }

    fun setQuickDay(daysOffset: Long) {
        val currentTime = runCatching { LocalDateTime.parse(time, format).toLocalTime() }.getOrDefault(LocalTime.now())
        time = LocalDateTime.of(LocalDate.now().plusDays(daysOffset), currentTime).format(format)
    }

    fun save(again: Boolean) {
        val amount = preview
        if (amount == null || amount <= 0) { issues = "请输入大于 0 的金额"; return }
        val timestamp = runCatching { LocalDateTime.parse(time, format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        if (timestamp == null) { issues = "日期格式应为 yyyy-MM-dd HH:mm"; return }
        if (Regex("\\d{5,}").containsMatchIn(account)) { issues = "银行卡账户只填后四位"; return }
        val entry = Txn(
            cents = amount,
            merchant = name.trim().ifBlank { category },
            category = category,
            direction = direction,
            time = timestamp,
            account = account.trim(),
            note = note.trim()
        )
        s.linkRefund(entry)
        s.save(entry)
        onSavedCelebration()
        if (!again) finish() else {
            expr = ""; name = ""; note = ""; time = LocalDateTime.now().format(format)
            issues = "已记下，可以继续记下一笔。"
        }
    }

    ScrollPage {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chips(listOf("支出", "收入", "转账", "退款"), direction) {
                direction = it
                category = if (it == "收入" || it == "退款") "收入" else "餐饮"
            }
        }

        HardCard(color = theme.primary) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$direction · 金额（元）", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Ink.copy(alpha = 0.78f))
                Text(
                    if (expr.any { it == '+' || it == '−' } && preview != null) "合计 ¥${money(preview)}" else "支持 + − 连算",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.copy(alpha = 0.78f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CategoryIconBox(category, size = 40.dp, circle = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "¥ " + expr.ifBlank { "0.00" },
                        color = Ink,
                        style = moneyStyle(34.sp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(3.dp, 30.dp)
                            .graphicsLayer { alpha = cursorAlpha }
                            .background(Blue, RoundedCornerShape(2.dp))
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            allChoices.chunked(5).forEach { rowChoices ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    rowChoices.forEach { choice ->
                        val isSelected = category == choice.name
                        val cScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.08f else 1f,
                            animationSpec = spring(dampingRatio = 0.48f, stiffness = 700f),
                            label = "cat_jelly"
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .scale(cScale)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { category = choice.name }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, Blue, CircleShape).padding(2.dp)
                                            else Modifier
                                        )
                                        .background(Color(choice.color), CircleShape)
                                        .border(2.2.dp, Ink, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CategoryIcon(choice.name, Modifier.size(22.dp))
                                }
                                if (isSelected) {
                                    Box(
                                        Modifier
                                            .size(16.dp)
                                            .background(Blue, CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                choice.name,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                color = if (isSelected) Blue else Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    repeat(5 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (suggestedMerchants.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("常用：", fontSize = 11.5.sp, color = Muted, fontWeight = FontWeight.Bold)
                suggestedMerchants.forEach { item ->
                    val active = name == item
                    Box(
                        Modifier
                            .background(if (active) theme.primary else Color.White, RoundedCornerShape(50))
                            .border(1.8.dp, Ink, RoundedCornerShape(50))
                            .clickable { name = item }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(item, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                }
            }
        }

        Field(name, "商户 / 交易名称（可选）") { name = it }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val datePrefix = time.take(10)
            val todayStr = LocalDate.now().toString()
            val yestStr = LocalDate.now().minusDays(1).toString()
            Pill("今天", if (datePrefix == todayStr) theme.primary else Color.White) { setQuickDay(0) }
            Pill("昨天", if (datePrefix == yestStr) theme.primary else Color.White) { setQuickDay(-1) }
            Pill(if (extra) "收起时间、账户和备注" else "时间、账户和备注 · $account", Color.White) { extra = !extra }
        }

        AnimatedVisibility(visible = extra) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field(time, "时间 yyyy-MM-dd HH:mm") { time = it }
                Chips(s.accounts(), account) { account = it }
                Field(account, "账户（银行卡只填后四位）") { account = it }
                Field(note, "备注 / 标签") { note = it }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                listOf("7", "8", "9", "⌫"),
                listOf("4", "5", "6", "+"),
                listOf("1", "2", "3", "−"),
                listOf("0", ".", "清除", "=")
            ).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        val isFunc = key in listOf("⌫", "+", "−", "清除", "=")
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val shift by animateDpAsState(
                            if (pressed) 2.dp else 0.dp,
                            spring(dampingRatio = 0.55f, stiffness = 900f),
                            label = "key_shift"
                        )
                        val keyScale by animateFloatAsState(
                            if (pressed) 0.91f else 1f,
                            spring(dampingRatio = 0.48f, stiffness = 850f),
                            label = "key_scale"
                        )
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(50.dp)
                                .scale(keyScale)
                                .padding(end = 2.dp, bottom = 2.dp)
                        ) {
                            Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, shape))
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .offset(shift, shift)
                                    .background(
                                        when {
                                            key == "=" -> theme.primary
                                            isFunc -> theme.cardAlt
                                            else -> Color.White
                                        },
                                        shape
                                    )
                                    .border(2.2.dp, Ink, shape)
                                    .clip(shape)
                                    .clickable(interactionSource = interaction, indication = null) {
                                        issues = ""
                                        val tail = expr.substringAfterLast('+').substringAfterLast('−')
                                        when {
                                            key == "⌫" -> expr = expr.dropLast(1)
                                            key == "清除" -> expr = ""
                                            key == "=" && preview != null && preview >= 0 -> expr = money(preview)
                                            key == "=" -> issues = "请检查算式"
                                            expr.length >= 30 -> Unit
                                            key in listOf("+", "−") && tail.isNotEmpty() && !tail.endsWith(".") -> expr += key
                                            key == "." && '.' !in tail -> expr += if (tail.isEmpty()) "0." else "."
                                            key.all(Char::isDigit) && ('.' !in tail || tail.substringAfter('.').length < 2) -> expr += key
                                        }
                                    }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    key,
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (key == "清除") 14.sp else 19.sp,
                                    textAlign = TextAlign.Center,
                                    color = Ink
                                )
                            }
                        }
                    }
                }
            }
        }

        if (issues.isNotEmpty()) {
            Text(
                issues,
                color = if (issues.startsWith("已")) GreenIncome else DangerRed,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Pill("完成", theme.primary) { save(false) }
            Pill("保存，再记", Color.White) { save(true) }
            TextButton(onClick = finish) { Text("取消", color = Ink, fontWeight = FontWeight.Bold) }
        }
    }
}
