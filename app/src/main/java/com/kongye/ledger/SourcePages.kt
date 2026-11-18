package com.kongye.ledger

import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceSettings(
    context: Context, s: Store, revision: Int, onNotify: () -> Unit, onSms: () -> Unit,
    onBattery: () -> Unit, onImport: () -> Unit, onScan: () -> Unit, onSamples: () -> Unit,
    onAppInfo: () -> Unit
) {
    val today = LocalDate.now()
    val raw = remember(revision) { s.rows("RawEvent") }
    val allowed = remember(revision) { notificationAccess(context) }
    val enabled = s.setting("notifications", "true") == "true"
    val online = enabled && allowed && PaymentListener.connected
    val last = raw.asSequence().filter { it.optString("source") == "notification" }.maxOfOrNull { it.optLong("time") }
    var packages by rememberSaveable { mutableStateOf(s.setting("packages")) }
    var senders by rememberSaveable { mutableStateOf(s.setting("senders")) }
    var saved by remember { mutableStateOf(false) }

    ScrollPage {
        // E7 Duty Status Hero Card with Mascot
        HardCard(color = if (online) Navy else Coral) {
            val foreground = if (online) Color.White else Ink
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (online) Color(0xff7be08f) else Yellow, CircleShape)
                        )
                        Text(
                            if (online) "噗噗正在后台值班" else "值班状态待检查",
                            color = foreground,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            !enabled -> "通知采集已暂停"
                            !allowed -> "还没开启通知使用权"
                            !online -> "监听暂未连接"
                            else -> "噗噗值班中"
                        },
                        color = foreground,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("今天捕获 ${raw.count { day(it.optLong("time")) == today }} 条原始事件", color = foreground, fontWeight = FontWeight.Bold)
                    Text("上次捕获通知 · ${last?.let(::captureDate) ?: "暂无"}", fontSize = 12.sp, color = foreground.copy(alpha = 0.85f))
                }
                Pupu(if (online) "ok" else "sad", Modifier.size(84.dp, 94.dp))
            }
        }

        PermissionCards(s, onNotify, onSms, onBattery)

        if (allowed && !online && enabled) {
            Pill("尝试恢复连接", Yellow) {
                android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, PaymentListener::class.java))
            }
        }

        HardCard {
            Text("一加后台设置", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("在应用详情的耗电管理中允许后台活动和自启动，并在最近任务中锁定空叶记账。", color = Muted, fontSize = 13.sp)
            TextButton(onClick = onAppInfo) { Text("打开应用详情 →", color = Ink, fontWeight = FontWeight.Bold) }
        }

        HardCard {
            Text("采集渠道与白名单", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Toggle("付款通知采集（微信 / 支付宝 / 云闪付）", enabled) {
                s.set("notifications", it.toString())
                if (it && allowed) android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, PaymentListener::class.java))
            }
            Toggle("银行动账短信（招行 95555 / 建行 95533 等）", s.setting("sms", "true") == "true") { s.set("sms", it.toString()) }
            Field(packages, "额外银行 App 包名，逗号分隔") { packages = it; saved = false }
            Field(senders, "额外银行短信号码，逗号分隔") { senders = it; saved = false }
            Pill(if (saved) "白名单已保存" else "保存白名单", Color.White) {
                s.set("packages", packages.replace('，', ',').split(',').map(String::trim).filter(String::isNotEmpty).distinct().joinToString(","))
                s.set("senders", senders.replace('，', ',').split(',').map(String::trim).filter(String::isNotEmpty).distinct().joinToString(","))
                saved = true
            }
        }

        HardCard {
            Text("对账与样本库", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("支持直接导入微信或支付宝导出的 CSV / XLSX 账单，自动去重补漏。", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("采样记录", Yellow, onClick = onSamples)
                Pill("导入微信 / 支付宝账单", Color.White, onImport)
                Pill("补读银行短信", Color.White, onScan)
            }
        }
    }
}

private fun captureDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable
fun SampleRecords(s: Store, revision: Int, onReparse: () -> Unit, onExport: () -> Unit) {
    val records = remember(revision) { s.rows("RawEvent").sortedByDescending { it.optLong("time") } }
    var expanded by rememberSaveable { mutableStateOf("") }
    var onlyFailed by rememberSaveable { mutableStateOf(false) }
    val failed = records.count { it.optString("status") in listOf("", "未识别") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            HardCard(color = Color(0xffb9d8ea)) {
                Text("${records.size} 条样本 · $failed 条待解析", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("点开可以核对捕获时间、原文和识别结果。", fontSize = 13.sp, color = Ink)
            }
            Toggle("只看未识别样本", onlyFailed) { onlyFailed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("重新解析未识别样本", Yellow, onReparse)
                Pill("导出采样原文", Color.White, onExport)
            }
        }
        if (records.isEmpty()) item { Text("还没有采集到样本。", color = Muted) }
        items(records.filter { !onlyFailed || it.optString("status") in listOf("", "未识别") }, key = { it.getString("id") }) { row ->
            val id = row.getString("id")
            val origin = if (row.optString("source") == "sms") "短信" else "通知"
            HardCard {
                Text("$origin · ${captureDate(row.optLong("time"))} · ${row.optString("status").ifBlank { "旧版样本" }}", fontWeight = FontWeight.Bold)
                Text(row.optString("reason").ifBlank { row.optString("sender") }, fontSize = 13.sp, color = Muted)
                TextButton(onClick = { expanded = if (expanded == id) "" else id }) {
                    Text(if (expanded == id) "收起" else "查看原文", color = Ink, fontWeight = FontWeight.Bold)
                }
                if (expanded == id) {
                    Text(row.optString("sender"), fontSize = 12.sp, color = Muted)
                    listOf("title", "body", "text", "bigText", "subText").map { row.optString(it) }
                        .filter(String::isNotBlank).distinct().forEach { Text(it, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
fun StampGroupSelectorCard(s: Store) {
    val curGroup = LocalStampGroup.current
    HardCard {
        Text("日历印章样式", fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text("三组圆润猪猪轮廓印章，点击任一组即可立即应用到心情日历与统计。", fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stampGroups.forEach { grp ->
                val isSelected = grp.key == curGroup
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) Yellow.copy(alpha = 0.35f) else Cream, RoundedCornerShape(18.dp))
                        .border(if (isSelected) 2.4.dp else 1.8.dp, if (isSelected) Blue else Ink, RoundedCornerShape(18.dp))
                        .clickable { s.set("stampGroup", grp.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(grp.title, fontWeight = FontWeight.Black, fontSize = 13.5.sp, color = Ink)
                            if (isSelected) {
                                Box(
                                    Modifier
                                        .background(Yellow, RoundedCornerShape(50))
                                        .border(1.4.dp, Ink, RoundedCornerShape(50))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text("使用中", fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = Ink)
                                }
                            }
                        }
                        Text(grp.subtitle, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("happy", "ok", "sad").forEach { mood ->
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(stampDrawableRes(mood, grp.key)),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp, 22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoodThreshold(s: Store) {
    var threshold by remember { mutableFloatStateOf(s.setting("threshold", "80").toFloatOrNull()?.coerceIn(50f, 100f) ?: 80f) }
    HardCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("表情开心阈值 ${threshold.toInt()}%", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stamp("happy", Modifier.size(26.dp, 19.dp))
                Stamp("ok", Modifier.size(26.dp, 19.dp))
                Stamp("sad", Modifier.size(26.dp, 19.dp))
            }
        }
        Text(
            "花销 ≤ 日额度 ${threshold.toInt()}% 盖开心章 · ${threshold.toInt()}%–100% 盖平稳章 · 超出盖委屈章",
            fontSize = 12.sp,
            color = Muted
        )
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            valueRange = 50f..100f,
            steps = 49,
            onValueChangeFinished = { s.set("threshold", threshold.toInt().toString()) }
        )
    }
}

@Composable
fun MoreSettings(context: Context, s: Store, onImportRules: () -> Unit, onExportRules: () -> Unit) {
    var review by rememberSaveable { mutableStateOf(s.setting("reviewTime", "21:30")) }
    var retention by rememberSaveable { mutableStateOf(s.setting("retention", "180")) }
    var note by remember { mutableStateOf("") }
    HardCard {
        Text("提醒与样本", fontWeight = FontWeight.Black, fontSize = 17.sp)
        Field(review, "花销评分提醒时间（HH:mm）") { review = it; note = "" }
        Field(retention, "原始事件保留天数", true) { retention = it; note = "" }
        if (note.isNotEmpty()) Text(note, color = if (note == "已保存") GreenIncome else DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Pill("保存设置", Yellow) {
            val time = runCatching { LocalTime.parse(review.trim()) }.getOrNull()
            val days = retention.trim().toIntOrNull()
            if (time == null || days == null || days !in 1..3650) {
                note = "请填写 HH:mm 格式的时间和 1–3650 天的保留时长。"
            } else {
                val normalized = time.format(DateTimeFormatter.ofPattern("HH:mm"))
                val changed = s.setting("reviewTime", "21:30") != normalized
                s.set("reviewTime", normalized)
                s.set("retention", days.toString())
                if (changed) scheduleReview(context, replace = true)
                note = "已保存"
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onImportRules) { Text("导入解析规则", color = Ink, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onExportRules) { Text("导出当前规则", color = Ink, fontWeight = FontWeight.Bold) }
        }
    }
}
