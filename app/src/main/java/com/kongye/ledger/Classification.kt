package com.kongye.ledger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

data class CategoryChoice(val name: String, val color: Long)

fun Store.categoryChoices(): List<CategoryChoice> {
    val saved = setting("customCategories")
    if (saved.isNotBlank()) runCatching {
        val list = JSONArray(saved)
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            CategoryChoice(item.getString("name"), item.getLong("color"))
        }.ifEmpty { error("empty categories") }
    }
    return categories.mapIndexed { index, name -> CategoryChoice(name, palette[index % palette.size]) }
}

fun Store.saveCategoryChoices(choices: List<CategoryChoice>) {
    val json = JSONArray(choices.map { JSONObject().put("name", it.name).put("color", it.color) })
    set("customCategories", json.toString())
}

fun Store.renameCategory(old: String, next: String) {
    if (old == next) return
    db.runInTransaction {
        txns().filter { it.category == old }.forEach { txn -> txn.category = next; put("Txn", txn.id, txn.json()) }
        rows("Rule").filter { it.optString("cat") == old }.forEach { rule ->
            put("Rule", "rule:" + rule.getString("word"), rule.put("cat", next))
        }
    }
    val today = java.time.LocalDate.now()
    val previous = categoryBudget(old, today)
    if (previous > 0) {
        if (categoryBudget(next, today) == 0L) setCategoryBudget(next, previous, today)
        setCategoryBudget(old, 0, today)
    }
}

fun Store.accounts(): List<String> = (listOf("现金", "微信", "支付宝") + setting("accounts").split('|')).filter(String::isNotBlank).distinct()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountCards(s: Store) {
    var account by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val txns = remember(s.revision.collectAsState().value) { s.txns().filter { it.status != "已忽略" } }
    val byAccount = remember(txns) {
        txns.groupBy { it.account.ifBlank { "现金" } }.mapValues { (_, list) -> list.sumOf(::netExpense) }
    }
    HardCard {
        Text("账户管理", fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("预置账户 · 现金、微信、支付宝。支持添加银行卡尾号或饭卡等账户。", color = Muted, fontSize = 12.5.sp)
        Spacer(Modifier.height(8.dp))
        listOf("现金", "微信", "支付宝").forEach { preset ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Yellow, CircleShape)
                        .border(1.8.dp, Ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(preset.take(1), fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(preset, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("累计净支出 ¥${money(byAccount[preset] ?: 0L)}", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
            }
        }
        val saved = s.setting("accounts").split('|').filter(String::isNotBlank)
        saved.forEach { name ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Sky, CircleShape)
                        .border(1.8.dp, Ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.take(1), fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("累计净支出 ¥${money(byAccount[name] ?: 0L)}", fontSize = 11.5.sp, color = Muted)
                }
                TextButton(onClick = { s.set("accounts", saved.filter { it != name }.joinToString("|")) }) {
                    Text("移除", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            }
        }
        Field(account, "账户名称（银行卡只填后四位）") { account = it; error = false }
        if (error) Text("请检查名称是否重复，银行卡只填写后四位。", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Pill("添加账户", Yellow) {
            val value = account.trim()
            if (value.isBlank() || value.length > 28 || value.contains('|') || Regex("\\d{5,}").containsMatchIn(value) || value in s.accounts()) error = true
            else { s.set("accounts", (saved + value).joinToString("|")); account = "" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassificationPage(s: Store, revision: Int) {
    val choices = remember(revision) { s.categoryChoices() }
    val rules = remember(revision) { s.rows("Rule") }
    var editing by remember { mutableStateOf<CategoryChoice?>(null) }
    var expanded by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("其他") }
    var keyword by rememberSaveable { mutableStateOf("") }
    val latest = System.currentTimeMillis() - 7L * 86400000
    val learnedCount = remember(rules) { rules.count { it.optBoolean("learned") } }

    ScrollPage {
        // E6 Learning Header Banner
        HardCard(color = Mint) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Pupu("happy", Modifier.size(54.dp, 60.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${rules.size} 条分类规则" + if (learnedCount > 0) " · 学会 $learnedCount 条新规则" else "",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Text("商户匹配多个规则时，优先使用较长的关键词。", color = Ink, fontSize = 12.5.sp)
                }
            }
        }

        HardCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("新增关键词自动归类", fontWeight = FontWeight.Black, fontSize = 15.sp)
                Pill("新增分类", Yellow) { editing = CategoryChoice("", palette.first()) }
            }
            Field(keyword, "商户关键词（例如：瑞幸、盒马）") { keyword = it }
            Chips(choices.map { it.name }, selected) { selected = it }
            Pill("添加关键词规则", Color.White) {
                if (keyword.isNotBlank()) {
                    s.rule(keyword.trim(), selected.takeIf { cat -> choices.any { it.name == cat } } ?: "其他")
                    keyword = ""
                }
            }
        }

        choices.forEachIndexed { index, choice ->
            HardCard(color = Color(choice.color).copy(alpha = 0.55f)) {
                val thisRules = rules.filter { it.optString("cat") == choice.name }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryIconBox(choice.name, size = 36.dp, circle = true)
                    Text(choice.name, Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 15.5.sp)
                    TextButton(onClick = { expanded = if (expanded == choice.name) "" else choice.name }) {
                        Text("${thisRules.size} 条规则", color = Ink, fontWeight = FontWeight.ExtraBold)
                    }
                }
                AnimatedVisibility(visible = expanded == choice.name) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        thisRules.forEach { rule ->
                            val word = rule.getString("word")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .border(1.5.dp, Ink, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(word, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                if (rule.optLong("created") > latest && rule.optBoolean("learned")) {
                                    Box(
                                        Modifier
                                            .padding(horizontal = 4.dp)
                                            .background(Yellow, RoundedCornerShape(50))
                                            .border(1.4.dp, Ink, RoundedCornerShape(50))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text("新", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                                TextButton(onClick = { s.delete("rule:$word") }) {
                                    Text("删除", color = DangerRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editing = choice }) { Text("编辑", color = Ink, fontWeight = FontWeight.Bold) }
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    s.saveCategoryChoices(choices.toMutableList().apply { add(index - 1, removeAt(index)) })
                                }
                            ) { Text("上移", fontWeight = FontWeight.Bold) }
                            TextButton(
                                enabled = index < choices.lastIndex,
                                onClick = {
                                    s.saveCategoryChoices(choices.toMutableList().apply { add(index + 1, removeAt(index)) })
                                }
                            ) { Text("下移", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { choice ->
        var name by remember(choice) { mutableStateOf(choice.name) }
        var color by remember(choice) { mutableLongStateOf(choice.color) }
        var error by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf(false) }
        val reserved = choice.name in listOf("其他", "收入")
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Cream,
            title = {
                Text(
                    if (deleting) "确认删除分类" else if (choice.name.isBlank()) "新增分类" else "编辑分类",
                    fontWeight = FontWeight.Black
                )
            },
            text = {
                if (deleting) Text("「${choice.name}」的账目和规则会移到「其他」。")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (reserved) Text(choice.name, fontWeight = FontWeight.Bold) else Field(name, "分类名称") { name = it; error = "" }
                    palette.chunked(4).forEach { row ->
                        Row { row.forEach { value -> Pill(if (color == value) "✓" else "○", Color(value)) { color = value } } }
                    }
                    if (error.isNotEmpty()) Text(error, color = DangerRed, fontWeight = FontWeight.Bold)
                    if (!reserved && choice.name.isNotBlank()) {
                        TextButton(onClick = { deleting = true }) { Text("删除分类", color = DangerRed, fontWeight = FontWeight.Bold) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = name.trim()
                    if (deleting) {
                        s.renameCategory(choice.name, "其他")
                        s.saveCategoryChoices(choices.filter { it.name != choice.name })
                        selected = "其他"; editing = null
                    } else if (next.isBlank() || next.length > 14 || choices.any { it.name == next && it.name != choice.name }) {
                        error = "请输入不重复的 1–14 字分类名。"
                    } else {
                        if (choice.name.isNotEmpty()) s.renameCategory(choice.name, next)
                        s.saveCategoryChoices(
                            if (choice.name.isEmpty()) choices + CategoryChoice(next, color)
                            else choices.map { if (it.name == choice.name) CategoryChoice(next, color) else it }
                        )
                        if (selected == choice.name) selected = next
                        editing = null
                    }
                }) { Text(if (deleting) "删除" else "保存", color = Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { if (deleting) deleting = false else editing = null }) { Text("取消", color = Ink) }
            }
        )
    }
}
