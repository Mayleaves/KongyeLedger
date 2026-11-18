package com.kongye.ledger

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarPage(
    s: Store,
    txns: List<Txn>,
    edit: (Txn) -> Unit,
    onAddForDate: (LocalDate) -> Unit = {}
) {
    val theme = LocalLedgerTheme.current
    val today = LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var choose by remember { mutableStateOf(false) }
    var stampedTarget by remember { mutableStateOf<LocalDate?>(null) }
    val thumpAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(dateText)
    val curGroup = LocalStampGroup.current

    fun triggerStampThump(targetDate: LocalDate) {
        stampedTarget = targetDate
        scope.launch {
            thumpAnim.snapTo(1.52f)
            thumpAnim.animateTo(
                targetValue = 0.86f,
                animationSpec = tween(95, easing = FastOutLinearInEasing)
            )
            thumpAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.44f, stiffness = 680f)
            )
        }
    }

    fun shift(amount: Long) {
        val target = month.plusMonths(amount)
        monthText = target.toString()
        dateText = if (target == YearMonth.from(today)) today.toString() else target.atDay(1).toString()
    }

    val net = remember(txns) {
        txns.filter { it.status != "已忽略" }.groupBy { day(it.time) }.mapValues { (_, entries) -> entries.sumOf(::netExpense) }
    }
    val selectedMood = s.calendarMood(selected, net[selected] ?: 0L, txns)

    ScrollPage {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill("‹", Color.White) { shift(-1) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${month.year}年 ${month.monthValue}月", fontWeight = FontWeight.Black, fontSize = 21.sp)
                val groupInfo = stampGroups.firstOrNull { it.key == curGroup } ?: stampGroups[1]
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .background(Color.White, RoundedCornerShape(50))
                        .border(1.6.dp, Ink, RoundedCornerShape(50))
                        .clickable {
                            val idx = stampGroups.indexOfFirst { it.key == curGroup }
                            val nextGroup = stampGroups[(idx + 1) % stampGroups.size].key
                            s.set("stampGroup", nextGroup)
                            triggerStampThump(selected)
                        }
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${groupInfo.title} · 点我切换",
                        fontSize = 10.5.sp,
                        color = Muted,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            Pill("›", Color.White) { shift(1) }
        }

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 700f)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(160)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(130)))
            },
            label = "calendar_month"
        ) { targetMonth ->
            HardCard(
                modifier = Modifier.pointerInput(targetMonth) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (drag > 56.dp.toPx()) shift(-1)
                            if (drag < -56.dp.toPx()) shift(1)
                        }
                    ) { event, distance -> event.consume(); drag += distance }
                }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Box(
                            Modifier.weight(1f).padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Muted, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val offset = targetMonth.atDay(1).dayOfWeek.value - 1
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat((targetMonth.lengthOfMonth() + offset + 6) / 7) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { col ->
                                val number = week * 7 + col - offset + 1
                                if (number !in 1..targetMonth.lengthOfMonth()) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    val date = targetMonth.atDay(number)
                                    val manual = s.setting("mood:$date").isNotEmpty()
                                    val isSelected = date == selected
                                    val isToday = date == today
                                    val cellScale = if (stampedTarget == date) thumpAnim.value else 1f
                                    val cellRot = if (stampedTarget == date) (thumpAnim.value - 1f) * -18f else 0f

                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .heightIn(min = 58.dp)
                                            .padding(1.5.dp)
                                            .background(
                                                when {
                                                    isSelected -> theme.primary
                                                    isToday -> Color(0xffdde8fd)
                                                    else -> Color.Transparent
                                                },
                                                RoundedCornerShape(12.dp)
                                            )
                                            .then(
                                                if (isSelected) Modifier.border(1.8.dp, Ink, RoundedCornerShape(12.dp))
                                                else Modifier
                                            )
                                            .combinedClickable(
                                                onClick = { dateText = date.toString() },
                                                onLongClick = if (date <= today) ({ dateText = date.toString(); choose = true }) else null
                                            )
                                            .padding(vertical = 3.dp)
                                            .semantics { contentDescription = "$date" + if (manual) "，手动印章" else "" },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        if (date > today) {
                                            Canvas(Modifier.size(26.dp)) {
                                                drawCircle(
                                                    Muted.copy(alpha = 0.55f),
                                                    radius = size.minDimension * .36f,
                                                    style = Stroke(
                                                        1.5.dp.toPx(),
                                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
                                                    )
                                                )
                                            }
                                        } else {
                                            Stamp(
                                                s.calendarMood(date, net[date] ?: 0, txns),
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(28.dp)
                                                    .scale(cellScale)
                                                    .rotate(cellRot)
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier
                                                .then(
                                                    if (isToday && !isSelected) Modifier.background(Blue, RoundedCornerShape(50)).padding(horizontal = 5.dp)
                                                    else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                number.toString(),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (isToday && !isSelected) Color.White else Ink
                                            )
                                        }
                                        if (manual) {
                                            Text("手动", fontSize = 8.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // D4 Bottom Sheet Style Day Summary Card + Instant 1-Tap Stamping
        HardCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val sheetScale = if (stampedTarget == selected) thumpAnim.value else 1f
                Stamp(
                    selectedMood,
                    Modifier.size(48.dp, 36.dp).scale(sheetScale)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${selected.monthValue}月${selected.dayOfMonth}日 · ${if (selected == today) "今天" else moodLabel(selectedMood, curGroup)}",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Text(
                        "净支出 ¥${money(net[selected] ?: 0)} · ${moodLabel(selectedMood, curGroup)}",
                        fontSize = 12.5.sp,
                        color = Muted,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (selected <= today) {
                    Pill("换印章", Color.White) { choose = true }
                }
                Pill("补记", theme.primary) { onAddForDate(selected) }
            }
            if (selected <= today) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("happy" to "开心", "ok" to "平稳", "sad" to "委屈").forEach { (mKey, mName) ->
                        val active = selectedMood == mKey && s.setting("mood:$selected") == mKey
                        Row(
                            Modifier
                                .weight(1f)
                                .background(if (active) theme.primary else theme.canvas, RoundedCornerShape(14.dp))
                                .border(1.8.dp, Ink, RoundedCornerShape(14.dp))
                                .clickable {
                                    s.set("mood:$selected", mKey)
                                    triggerStampThump(selected)
                                }
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Stamp(mKey, Modifier.size(26.dp, 19.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(mName, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                        }
                    }
                }
            }
        }

        val entries = txns.filter { day(it.time) == selected && it.status != "已忽略" }
        if (entries.isEmpty()) {
            HardCard(color = theme.cardAlt) {
                Text("这一天没有账目，点右上「补记」可以补录这一天的花销。", color = Muted, fontSize = 13.sp)
            }
        }
        entries.forEach { item -> TxnRow(item) { edit(item) } }
    }

    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            containerColor = theme.canvas,
            title = { Text("给 ${selected.monthValue}月${selected.dayOfMonth}日 盖章", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("开心", "happy", Pink),
                        Triple("平稳", "ok", Yellow),
                        Triple("委屈", "sad", Sky)
                    ).forEach { (label, state, bg) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(bg.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                .border(2.dp, Ink, RoundedCornerShape(16.dp))
                                .clickable {
                                    s.set("mood:$selected", state)
                                    choose = false
                                    triggerStampThump(selected)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Stamp(state, Modifier.size(42.dp, 30.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink)
                                Text(moodLabel(state, curGroup), fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                            }
                            Text("盖章 →", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Ink)
                        }
                    }
                    TextButton(
                        onClick = { s.set("mood:$selected", ""); choose = false; triggerStampThump(selected) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("恢复自动判定", color = Ink, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choose = false }) { Text("取消", color = Ink, fontWeight = FontWeight.Bold) }
            }
        )
    }
}

internal fun netExpense(item: Txn): Long = when (item.direction) {
    "支出" -> item.cents
    "退款" -> -item.cents
    else -> 0L
}

@Composable
fun StatsPage(s: Store, txns: List<Txn>, report: Boolean, showPrevious: Boolean, onCat: (String) -> Unit) {
    val theme = LocalLedgerTheme.current
    var period by rememberSaveable { mutableStateOf("月") }
    var previous by rememberSaveable(showPrevious) { mutableStateOf(showPrevious) }
    val today = LocalDate.now()
    val yearMonth = YearMonth.from(today).minusMonths(if (report && previous) 1 else 0)
    val start = when (period) {
        "周" -> today.minusDays(today.dayOfWeek.value - 1L)
        "年" -> today.withDayOfYear(1)
        else -> yearMonth.atDay(1)
    }
    val end = if (report && previous) yearMonth.atEndOfMonth() else today
    val entries = remember(txns, start, end) { txns.filter { it.status != "已忽略" && day(it.time) in start..end } }
    val byDate = remember(entries) { entries.groupBy { day(it.time) }.mapValues { (_, ls) -> ls.sumOf(::netExpense) } }
    val byCategory = remember(entries) {
        entries.filter { it.direction == "支出" }.groupBy { it.category }
            .mapValues { (_, ls) -> ls.sumOf { it.cents } }.toList().sortedByDescending { it.second }
    }
    val categoryColors = byCategory.associate { it.first to catColor(it.first) }
    val gross = byCategory.sumOf { it.second }
    val net = byDate.values.sum()
    val totalIncome = remember(entries) { entries.filter { it.direction == "收入" }.sumOf { it.cents } }
    val daysSpan = java.time.temporal.ChronoUnit.DAYS.between(start, end).coerceAtLeast(0) + 1
    val dailyAvg = if (daysSpan > 0) net / daysSpan else 0L
    val dateRangeList = remember(start, end) {
        generateSequence(maxOf(start, end.minusDays(30))) { it.plusDays(1).takeIf { next -> next <= end } }.toList()
    }
    val lastDays = remember(dateRangeList, byDate) { dateRangeList.map { byDate[it] ?: 0L } }
    val peakEntry = remember(byDate) { byDate.maxByOrNull { it.value }?.takeIf { it.value > 0 } }

    var chartTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(period, previous, gross) { chartTriggered = true }
    val chartProgress by animateFloatAsState(
        targetValue = if (chartTriggered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 420f),
        label = "donut_sweep"
    )

    ScrollPage {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (report) {
                Chips(listOf("本月", "上月"), if (previous) "上月" else "本月") { previous = it == "上月" }
            } else {
                Chips(listOf("周", "月", "年"), period) { period = it }
            }
            Text("$start 至 $end", color = Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }

        HardCard(color = theme.primary) {
            Text(
                if (report) "${yearMonth.monthValue} 月净支出" else "本${period}净支出",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                color = Ink.copy(alpha = 0.78f)
            )
            AnimatedMoneyText(cents = net, style = moneyStyle(34.sp), color = Ink)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("收入 ¥${money(totalIncome)}", color = Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text("日均 ¥${money(dailyAvg)}", color = Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text("共 ${entries.size} 笔", color = Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        HardCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("支出构成", fontWeight = FontWeight.Black, fontSize = 17.sp)
                if (gross > 0) {
                    Text("总支出 ¥${money(gross)}", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(176.dp).semantics { contentDescription = "分类支出环形图" }) {
                    val line = 26.dp.toPx()
                    val bounds = Size(size.width - line, size.height - line)
                    val inset = Offset(line / 2, line / 2)
                    if (gross <= 0) {
                        drawArc(theme.canvas, 0f, 360f, false, inset, bounds, style = Stroke(line))
                        drawCircle(Ink, radius = (size.minDimension - line * 2) / 2f, style = Stroke(2.dp.toPx()))
                        drawCircle(Ink, radius = size.minDimension / 2f, style = Stroke(2.dp.toPx()))
                    } else {
                        var angle = -90f
                        byCategory.forEach { (cat, amount) ->
                            val sweep = (amount.toDouble() / gross * 360.0).toFloat() * chartProgress
                            drawArc(categoryColors.getValue(cat), angle, sweep, false, inset, bounds, style = Stroke(line))
                            angle += sweep
                        }
                        drawCircle(Ink, radius = (size.minDimension - line * 2) / 2f, style = Stroke(2.4.dp.toPx()))
                        drawCircle(Ink, radius = size.minDimension / 2f, style = Stroke(2.4.dp.toPx()))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val mood = s.calendarMood(end, byDate[end] ?: 0L, txns)
                    Stamp(mood, Modifier.size(60.dp, 44.dp))
                    if (gross > 0) {
                        Text(
                            "${byCategory.size} 类",
                            fontSize = 11.sp,
                            color = Muted,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            if (byCategory.isEmpty()) Text("这个时段没有支出。", color = Muted)
        }

        byCategory.forEach { (cat, amount) ->
            val ratio = if (gross <= 0) 0f else (amount.toFloat() / gross).coerceIn(0f, 1f)
            val pct = (ratio * 100).toInt()
            val cColor = catColor(cat)
            HardCard(Modifier.clickable { onCat(cat) }, Color.White) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryIconBox(cat, size = 36.dp, circle = true)
                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(cat, fontWeight = FontWeight.Black, fontSize = 15.sp)
                                Text("$pct%", fontSize = 11.5.sp, color = Muted, fontWeight = FontWeight.ExtraBold)
                            }
                            Text("¥${money(amount)}", fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        StripedProgressBar(
                            progress = ratio,
                            stripeColor = cColor,
                            stripeAltColor = cColor.copy(alpha = 0.72f),
                            modifier = Modifier.fillMaxWidth().height(12.dp)
                        )
                    }
                }
            }
        }

        HardCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (period == "年") "最近 31 天 · 每日净支出" else "每日净支出",
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                if (peakEntry != null) {
                    Box(
                        Modifier
                            .background(theme.canvas, RoundedCornerShape(50))
                            .border(1.5.dp, Ink, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "最高 ${peakEntry.key.monthValue}/${peakEntry.key.dayOfMonth} ¥${money(peakEntry.value)}",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Ink
                        )
                    }
                }
            }
            val peak = lastDays.maxOrNull()?.coerceAtLeast(1) ?: 1L
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(110.dp)
                    .semantics { contentDescription = "每日净支出柱形图" }
            ) {
                val count = lastDays.size.coerceAtLeast(1)
                val column = size.width / count
                val barW = (column * .70f).coerceAtLeast(3.dp.toPx())
                lastDays.forEachIndexed { index, amount ->
                    val rawH = if (amount <= 0) 3.dp.toPx() else (amount.toDouble() / peak * (size.height - 6.dp.toPx())).toFloat() * chartProgress
                    val height = rawH.coerceAtLeast(3.dp.toPx())
                    val isLast = index == lastDays.lastIndex
                    val isPeak = amount > 0 && amount == peak
                    val fillColor = when {
                        amount <= 0 -> theme.canvas
                        isLast -> Blue
                        isPeak -> Coral
                        else -> theme.primary
                    }
                    val topLeft = Offset(index * column + (column - barW) / 2f, size.height - height)
                    val barSize = Size(barW, height)
                    drawRoundRect(
                        color = fillColor,
                        topLeft = topLeft,
                        size = barSize,
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    if (amount > 0 && barW >= 5.dp.toPx()) {
                        drawRoundRect(
                            color = Ink,
                            topLeft = topLeft,
                            size = barSize,
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(1.5.dp.toPx())
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${maxOf(start, end.minusDays(30))}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$end", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (report) {
            val most = byDate.maxByOrNull { it.value }?.takeIf { it.value > 0 }
            val moods = generateSequence(start) { it.plusDays(1).takeIf { next -> next <= end } }
                .groupingBy { date -> s.calendarMood(date, byDate[date] ?: 0L, txns) }.eachCount()
            val happy = entries.filter { it.direction == "支出" && it.score >= 70 }
            val regret = entries.filter { it.direction == "支出" && it.score in 0..30 }
            HardCard(color = theme.secondary) {
                Text("这个月的心情", fontWeight = FontWeight.Black, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple("开心", "happy", moods["happy"] ?: 0),
                        Triple("平稳", "ok", moods["ok"] ?: 0),
                        Triple("委屈", "sad", moods["sad"] ?: 0)
                    ).forEach { (label, moodKey, count) ->
                        Column(
                            Modifier
                                .weight(1f)
                                .background(Color.White, RoundedCornerShape(14.dp))
                                .border(1.8.dp, Ink, RoundedCornerShape(14.dp))
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Stamp(moodKey, Modifier.size(36.dp, 26.dp))
                            Spacer(Modifier.height(2.dp))
                            Text("$label $count 天", fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("开心 ${moods["happy"] ?: 0} 天 · 平稳 ${moods["ok"] ?: 0} 天 · 委屈 ${moods["sad"] ?: 0} 天", fontSize = 12.sp, color = Ink)
                Text("开心花销 ${happy.size} 笔 / ¥${money(happy.sumOf { it.cents })}", fontWeight = FontWeight.Bold)
                Text("后悔花销 ${regret.size} 笔 / ¥${money(regret.sumOf { it.cents })}", fontWeight = FontWeight.Bold)
                Text(
                    if (most == null) "暂无最高支出日" else "最贵的一天 ${most.key} · ¥${money(most.value)}",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBudgetCards(s: Store, txns: List<Txn>) {
    val theme = LocalLedgerTheme.current
    val today = LocalDate.now()
    val (start, end) = s.cycle(today)
    val usage = remember(txns, start, end) {
        txns.filter { it.status != "已忽略" && day(it.time) >= start && day(it.time) < end }
            .groupBy { it.category }.mapValues { (_, entries) -> entries.sumOf(::netExpense) }
    }
    val revision by s.revision.collectAsState()
    val names = remember(revision) { s.categoryChoices().map { it.name } }
    val budgets = remember(revision, start) { names.mapNotNull { cat -> s.categoryBudget(cat, today).takeIf { it > 0 }?.let { cat to it } } }
    var selected by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("分类预算", fontSize = 19.sp, fontWeight = FontWeight.Black)
            Pill("设置分类预算", Color.White) { selected = names.first() }
        }
        if (budgets.isEmpty()) {
            HardCard(color = Color.White) {
                Text("还没有设置分类预算，点右上「设置分类预算」可按餐饮、购物等分类单独控额。", fontSize = 13.sp, color = Muted)
            }
        }
        budgets.forEach { (category, amount) ->
            val spent = usage[category] ?: 0L
            val ratio = (spent.toFloat() / amount).coerceIn(0f, 1f)
            val catMood = when {
                spent <= amount * 4 / 5 -> "happy"
                spent <= amount -> "ok"
                else -> "sad"
            }
            val cColor = catColor(category)
            HardCard(Modifier.clickable { selected = category }, Color.White) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryIconBox(category, size = 36.dp, circle = true)
                    Column(Modifier.weight(1f)) {
                        Text(category, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text("已用 ¥${money(spent)} / ¥${money(amount)}", fontSize = 12.5.sp, color = Muted, fontWeight = FontWeight.Bold)
                    }
                    Stamp(catMood, Modifier.size(36.dp, 26.dp))
                }
                Spacer(Modifier.height(8.dp))
                StripedProgressBar(
                    progress = ratio,
                    stripeColor = if (spent >= amount) Coral else cColor,
                    stripeAltColor = if (spent >= amount) DangerRed.copy(alpha = 0.7f) else cColor.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().height(12.dp)
                )
            }
        }
    }
    selected?.let { category ->
        var amount by remember(category) { mutableStateOf(s.categoryBudget(category, today).takeIf { it > 0 }?.let(::money).orEmpty()) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = theme.canvas,
            title = { Text("分类预算", fontWeight = FontWeight.Black) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chips(names, category) { selected = it; error = false }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("300", "500", "1000", "1500").forEach { preset ->
                            Pill("¥$preset", if (amount == preset) theme.primary else Color.White) { amount = preset; error = false }
                        }
                    }
                    Field(amount, "金额（元），填 0 取消预算", true) { amount = it; error = false }
                    if (error) Text("请输入有效金额", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = if (amount.isBlank()) 0L else runCatching { cents(amount) }.getOrNull()
                    if (value == null || value < 0) error = true
                    else { s.setCategoryBudget(category, value, today); selected = null }
                }) { Text("保存", color = Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("取消", color = Ink) }
            }
        )
    }
}
