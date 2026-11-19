package com.kongye.ledger

import android.content.*
import android.os.*
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

val Cream = Color(0xfff7f0e3)
val Ink = Color(0xff33281f)
val Yellow = Color(0xfff6c945)
val Blue = Color(0xff3d6df2)
val Muted = Color(0xff8a7862)
val palette = listOf(0xfffbe39a, 0xffb9d8ea, 0xfff7b3c2, 0xffc9e7c3, 0xffddd0f4, 0xfff4a58e, 0xff8ccae6)

private val LocalLedgerCategories = compositionLocalOf<List<CategoryChoice>> { emptyList() }

@Composable
fun catColor(cat: String) = Color(
    LocalLedgerCategories.current.firstOrNull { it.name == cat }?.color
        ?: palette[categories.indexOf(cat).coerceAtLeast(0) % palette.size]
)

fun moneyStyle(size: TextUnit) = TextStyle(fontSize = size, fontWeight = FontWeight.Black, fontFeatureSettings = "tnum")

class MainActivity : ComponentActivity() {
    private var store: Store? = null
    var entryIntent by mutableStateOf<Intent?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entryIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        entryIntent = intent
        setContent {
            var ledger by remember { mutableStateOf<Store?>(null) }
            var ledgerPresented by remember { mutableStateOf(false) }
            var startupProgress by remember { mutableFloatStateOf(0f) }
            var webViewPrepared by remember { mutableStateOf(false) }
            var showLedger by remember { mutableStateOf(false) }
            var introFinished by remember { mutableStateOf(false) }
            var initializationFailed by remember { mutableStateOf(false) }
            var attempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                prepareLedgerWebView(applicationContext)
                webViewPrepared = true
            }
            LaunchedEffect(attempt) {
                startupProgress = 0f
                try {
                    val opened = withContext(Dispatchers.IO) {
                        Store.get(applicationContext).also { ledgerStore ->
                            if (ledgerStore.setting("firstMetDate").isBlank()) {
                                ledgerStore.set("firstMetDate", LocalDate.now().toString())
                            }
                            if (ledgerStore.setting("onboarded") != "true") ledgerStore.set("onboarded", "true")
                            schedule(applicationContext)
                        }
                    }
                    store = opened
                    startupProgress = .20f
                    ledger = opened
                    refreshLedger(opened)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("LedgerStartup", "Unable to initialize the ledger", error)
                    initializationFailed = true
                }
            }
            val splashVisible = !introFinished || (!ledgerPresented && !initializationFailed)
            fun finishIntro() {
                showLedger = true
                introFinished = true
            }
            val background = colorResource(R.color.ledger_launch_background)
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff2f68e6), background = background)) {
                Box(Modifier.fillMaxSize().background(background)) {
                    if (initializationFailed) {
                        LedgerLoadError(background) { initializationFailed = false; attempt++ }
                    } else if (showLedger && webViewPrepared) {
                        ledger?.let {
                            LedgerWebScreen(
                                this@MainActivity,
                                it,
                                onStartupProgress = { fraction -> startupProgress = maxOf(startupProgress, fraction) },
                                onPresented = { ready ->
                                    if (ready) startupProgress = 1f
                                    ledgerPresented = true
                                }
                            )
                        }
                    }
                    BackHandler(splashVisible) { finishIntro() }
                    AnimatedVisibility(visible = splashVisible, exit = fadeOut(tween(240))) {
                        SplashScreenOverlay(
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                            readyToEnter = ledgerPresented || initializationFailed,
                            loadProgress = startupProgress,
                            onEntranceFinished = { showLedger = true },
                            onSkip = { finishIntro() },
                            onFinished = { finishIntro() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        store?.let(::refreshLedger)
    }

    private fun refreshLedger(store: Store) {
        store.changed()
        Thread { runCatching { store.takeDaySnapshot(); readSms(this) } }.start()
    }
}

@Composable
fun Pupu(state: String, modifier: Modifier = Modifier) {
    AnimatedPupu(state = state, modifier = modifier, bobbing = true)
}

@Composable
fun Stamp(state: String, modifier: Modifier = Modifier) {
    val group = LocalStampGroup.current
    Crossfade(targetState = state to group, animationSpec = tween(160), modifier = modifier, label = "stamp_fade") { (st, grp) ->
        Image(
            painterResource(stampDrawableRes(st, grp)),
            contentDescription = "心情印章",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun HardCard(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(4.dp, 4.dp)
                .background(Ink, RoundedCornerShape(24.dp))
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(color, RoundedCornerShape(24.dp))
                .border(2.5.dp, Ink, RoundedCornerShape(24.dp))
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
fun Pill(text: String, color: Color = LocalLedgerTheme.current.primary, onClick: () -> Unit) {
    Pill(text = text, color = color, modifier = Modifier, onClick = onClick)
}

@Composable
fun Pill(
    text: String,
    color: Color = LocalLedgerTheme.current.primary,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(
        if (pressed) 2.dp else 0.dp,
        spring(dampingRatio = 0.55f, stiffness = 850f),
        label = "pill_shift"
    )
    val scale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.48f, stiffness = 800f),
        label = "pill_scale"
    )
    val shape = RoundedCornerShape(50)
    Box(modifier.scale(scale).padding(3.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, shape))
        Box(
            Modifier
                .offset(shift, shift)
                .background(color, shape)
                .border(2.dp, Ink, shape)
                .clip(shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (color == Blue || color == Navy) Color.White else Ink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.5.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(values: List<String>, selected: String, onClick: (String) -> Unit) {
    val activeColor = LocalLedgerTheme.current.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEach {
            Pill(it, if (it == selected) activeColor else Color.White) { onClick(it) }
        }
    }
}

@Composable
fun Field(value: String, label: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Ink,
            unfocusedBorderColor = Ink.copy(alpha = 0.65f)
        ),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}

private fun pageOrder(p: String): Int = when (p) {
    "欢迎" -> 0
    "自动记账" -> 1
    "权限" -> 2
    "首页" -> 10
    "流水" -> 11
    "记一笔" -> 12
    "统计" -> 13
    "我的" -> 14
    "心情日历" -> 20
    "预算" -> 21
    "值不值" -> 22
    "月报" -> 23
    "分类与规则" -> 24
    "账户" -> 25
    "数据来源" -> 26
    "采样记录" -> 27
    else -> 30
}

private fun seedDemoTransactions(s: Store, today: LocalDate) {
    val zone = ZoneId.systemDefault()
    fun ts(daysAgo: Long, hour: Int, min: Int): Long =
        LocalDateTime.of(today.minusDays(daysAgo), LocalTime.of(hour, min)).atZone(zone).toInstant().toEpochMilli()

    val samples = listOf(
        Txn(cents = 1590, merchant = "瑞幸咖啡", category = "餐饮", direction = "支出", time = ts(0, 9, 15), source = "自动", account = "微信", note = "生椰拿铁"),
        Txn(cents = 400, merchant = "地铁 2 号线", category = "交通", direction = "支出", time = ts(0, 8, 35), source = "自动", account = "支付宝", note = "通勤"),
        Txn(cents = 2850, merchant = "美团外卖", category = "餐饮", direction = "支出", time = ts(0, 12, 20), source = "自动", account = "微信", note = "工作日午餐"),
        Txn(cents = 2360, merchant = "滴滴出行", category = "交通", direction = "支出", time = ts(0, 18, 40), source = "自动", status = "待确认", account = "8812", note = "晚高峰打车"),
        Txn(cents = 8640, merchant = "盒马鲜生", category = "购物", direction = "支出", time = ts(1, 19, 10), source = "自动", account = "微信", note = "水果酸奶", score = 88),
        Txn(cents = 3200, merchant = "楼下牛肉面", category = "餐饮", direction = "支出", time = ts(1, 12, 15), source = "手动", account = "微信"),
        Txn(cents = 5800, merchant = "万达影城", category = "娱乐", direction = "支出", time = ts(2, 20, 0), source = "自动", account = "支付宝", note = "周末电影", score = 92),
        Txn(cents = 12800, merchant = "京东商城", category = "购物", direction = "支出", time = ts(3, 15, 30), source = "自动", account = "8812", note = "桌面收纳", score = 25),
        Txn(cents = 6500, merchant = "电费充值", category = "居住", direction = "支出", time = ts(4, 10, 0), source = "自动", account = "支付宝"),
        Txn(cents = 50000, merchant = "兼职稿费", category = "收入", direction = "收入", time = ts(2, 16, 0), source = "手动", account = "8812", note = "设计补贴")
    )
    samples.forEach { s.save(it) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerApp(a: MainActivity, s: Store) {
    val rev by s.revision.collectAsState()
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var coinBurstTrigger by remember { mutableIntStateOf(0) }
    var page by rememberSaveable {
        mutableStateOf(
            if (s.setting("onboarded") == "true") {
                if (a.intent.getBooleanExtra("review", false)) "值不值"
                else if (a.intent.getBooleanExtra("pending", false)) "流水"
                else "首页"
            } else "欢迎"
        )
    }
    var filter by rememberSaveable { mutableStateOf(if (a.intent.getBooleanExtra("pending", false)) "待确认" else "全部") }
    var entryInitialDate by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var swipeDelete by remember { mutableStateOf<Txn?>(null) }
    var report by remember { mutableStateOf("") }
    var exportType by rememberSaveable { mutableStateOf("backup") }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val importBill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val result = runCatching { a.contentResolver.openInputStream(uri)!!.use { BillImport.run(s, it.readBytes()) } }
                .fold({ it }, { "导入失败：${it.message}" })
            withContext(Dispatchers.Main) { report = result }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val r = runCatching { a.contentResolver.openInputStream(uri)!!.bufferedReader().use { s.restore(it.readText()) } }
            withContext(Dispatchers.Main) { report = if (r.isSuccess) "备份恢复完成" else "恢复失败：${r.exceptionOrNull()?.message}" }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            a.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                it.write(
                    when (exportType) {
                        "samples" -> org.json.JSONArray(s.rows("RawEvent")).toString(2)
                        "rules" -> s.parserText()
                        else -> s.backup()
                    }
                )
            }
        }.onSuccess { report = "导出完成" }.onFailure { report = "导出失败：${it.message}" }
    }
    val importRules = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            report = withContext(Dispatchers.IO) {
                runCatching {
                    a.contentResolver.openInputStream(uri)!!.bufferedReader().use { s.importParser(it.readText()) }
                    "规则已导入。可在采样记录中重新识别历史样本。"
                }.getOrElse { "规则导入失败，请检查 JSON 格式和字段。" }
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        s.changed()
        scope.launch(Dispatchers.IO) { readSms(a) }
    }

    val txns by produceState(initialValue = emptyList<Txn>(), s) {
        s.revision.collect { value = withContext(Dispatchers.IO) { s.txns() } }
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDate.now()
            if (now != today) {
                today = now
                withContext(Dispatchers.IO) { s.takeDaySnapshot() }
            }
            delay(15000)
        }
    }
    val pending = txns.filter { it.status == "待确认" }

    LaunchedEffect(a.entryIntent) {
        val entry = a.entryIntent ?: return@LaunchedEffect
        if (s.setting("onboarded") == "true") {
            if (entry.getBooleanExtra("pending", false)) {
                page = "流水"; filter = "待确认"
                editing = withContext(Dispatchers.IO) { s.txns().firstOrNull { it.status == "待确认" } }
            } else if (entry.getBooleanExtra("review", false)) page = "值不值"
            else if (entry.getBooleanExtra("sources", false)) page = "数据来源"
            else if (entry.getBooleanExtra("monthly", false)) page = "月报"
        }
    }

    val stampGroup = remember(rev) { s.setting("stampGroup", "g2").ifBlank { "g2" } }
    val activeTheme = remember(rev) { themeByKey(s.setting("theme", "cream")) }

    CompositionLocalProvider(
        LocalLedgerCategories provides remember(rev) { s.categoryChoices() },
        LocalStampGroup provides stampGroup,
        LocalLedgerTheme provides activeTheme
    ) {
        val onboarding = page in listOf("欢迎", "自动记账", "权限")
        val targetCanvas = when (page) {
            "欢迎" -> Navy
            "自动记账" -> Coral
            "权限" -> Sky
            "值不值" -> Color(0xfffbe39a)
            else -> activeTheme.canvas
        }
        val canvas by animateColorAsState(
            targetValue = targetCanvas,
            animationSpec = tween(210, easing = FastOutSlowInEasing),
            label = "canvas_bg"
        )
        val view = LocalView.current
        SideEffect {
            val statusDark = page != "欢迎" || showSplash
            a.window.decorView.setBackgroundColor((if (showSplash) activeTheme.canvas else canvas).toArgb())
            WindowCompat.getInsetsController(a.window, view).apply {
                isAppearanceLightStatusBars = statusDark
                isAppearanceLightNavigationBars = statusDark
            }
        }
        BackHandler(page != "首页" && page != "欢迎") {
            page = when (page) {
                "权限" -> "自动记账"
                "自动记账" -> "欢迎"
                "采样记录" -> "数据来源"
                else -> "首页"
            }
        }
        fun go(p: String) {
            if (p != "记一笔") entryInitialDate = null
            page = p
        }
        fun cycleTheme() {
            val idx = ledgerThemes.indexOfFirst { it.key == activeTheme.key }
            val next = ledgerThemes[(idx + 1) % ledgerThemes.size]
            s.set("theme", next.key)
        }
        fun remove(item: Txn) {
            s.delete(item.id)
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                val timer = launch { delay(5000); snack.currentSnackbarData?.dismiss() }
                if (snack.showSnackbar("已删除账目", "撤销", duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) s.save(item)
                timer.cancel()
            }
        }
        fun system(action: String) {
            runCatching { a.startActivity(Intent(action)) }.onFailure { report = "请在系统设置中打开对应权限。" }
        }
        val showNav = !onboarding && page != "记一笔" && !WindowInsets.isImeVisible

        Box(Modifier.fillMaxSize().background(canvas), contentAlignment = Alignment.TopCenter) {
            Scaffold(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = { if (showNav) LedgerNav(page) { go(it) } }
            ) { insets ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(insets)
                        .consumeWindowInsets(insets)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .then(if (showNav) Modifier else Modifier.navigationBarsPadding())
                        .imePadding()
                ) {
                    if (!onboarding) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (page !in listOf("首页", "流水", "统计", "我的")) {
                                    IconBtn("back", contentDescription = "返回") {
                                        go(if (page == "采样记录") "数据来源" else "首页")
                                    }
                                }
                                Column {
                                    Text(
                                        if (page == "首页") "今天" else page,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 26.sp,
                                        color = Ink
                                    )
                                    Text(
                                        today.format(DateTimeFormatter.ofPattern("M月d日 E", java.util.Locale.CHINA)) + " · ${activeTheme.title}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // One-tap theme dot switcher in top bar
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .background(activeTheme.primary, CircleShape)
                                        .border(2.dp, Ink, CircleShape)
                                        .clip(CircleShape)
                                        .clickable { cycleTheme() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .background(activeTheme.secondary, CircleShape)
                                            .border(1.5.dp, Ink, CircleShape)
                                    )
                                }
                                if (page == "首页") {
                                    Pill("日历", Color.White) { go("心情日历") }
                                } else {
                                    Pill("首页", Color.White) { go("首页") }
                                }
                            }
                        }
                    }

                    // Snappy 200ms Damped Q-Elastic Spring Page Transitions
                    AnimatedContent(
                        targetState = page,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            val from = initialState
                            val to = targetState
                            when {
                                to == "记一笔" -> {
                                    (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 720f)) { it / 4 } + fadeIn(tween(150))) togetherWith
                                        (scaleOut(targetScale = 0.96f, animationSpec = tween(160)) + fadeOut(tween(130)))
                                }
                                from == "记一笔" -> {
                                    (scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 700f)) + fadeIn(tween(150))) togetherWith
                                        (slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(130)))
                                }
                                pageOrder(to) >= pageOrder(from) -> {
                                    (slideInHorizontally(spring(dampingRatio = 0.72f, stiffness = 720f)) { it / 5 } + fadeIn(tween(150))) togetherWith
                                        (slideOutHorizontally(tween(160)) { -it / 6 } + fadeOut(tween(120)))
                                }
                                else -> {
                                    (slideInHorizontally(spring(dampingRatio = 0.72f, stiffness = 720f)) { -it / 5 } + fadeIn(tween(150))) togetherWith
                                        (slideOutHorizontally(tween(160)) { it / 5 } + fadeOut(tween(120)))
                                }
                            }
                        },
                        label = "page_transition"
                    ) { currentPage ->
                        when (currentPage) {
                            "欢迎", "自动记账" -> WelcomeScreen(
                                currentPage == "欢迎",
                                { go(if (currentPage == "欢迎") "自动记账" else "权限") }
                            ) {
                                s.set("onboarded", "true"); s.takeDaySnapshot(); go("首页")
                            }

                            "权限" -> SetupScreen(
                                s,
                                { system(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                                { permissions.launch(ledgerPermissions()) },
                                { system(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
                            ) {
                                s.set("onboarded", "true"); s.takeDaySnapshot(); go("首页")
                            }

                            "首页" -> ScrollPage {
                                val spent = txns.filter { it.status != "已忽略" && day(it.time) == today }.sumOf(::netExpense)
                                val q = s.quota(today, txns)
                                val left = q - spent
                                HomeMascot(s, txns, today, left, spent) { cat -> filter = cat; go("流水") }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Pill(
                                        if (pending.isEmpty()) "0 笔待确认" else "查看 ${pending.size} 笔待确认",
                                        if (pending.isEmpty()) Color.White else activeTheme.secondary
                                    ) {
                                        if (pending.isNotEmpty()) editing = pending.first() else { filter = "待确认"; go("流水") }
                                    }
                                    Pill("值不值", activeTheme.secondary) { go("值不值") }
                                    Pill("数据来源", Color.White) { go("数据来源") }
                                }

                                HardCard(color = Color.White) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Column {
                                            Text(
                                                if (left >= 0) "今天还能花" else "今天超了",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                                color = if (left >= 0) Muted else DangerRed
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            AnimatedMoneyText(
                                                cents = kotlin.math.abs(left),
                                                style = moneyStyle(34.sp),
                                                color = if (left >= 0) Ink else DangerRed
                                            )
                                        }
                                        Stamp(s.mood(today), Modifier.size(46.dp, 34.dp))
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    val ratio = if (q <= 0L) 1f else (spent.toFloat() / q).coerceIn(0f, 1f)
                                    StripedProgressBar(
                                        progress = ratio,
                                        stripeColor = if (left < 0) Coral else activeTheme.stripeA,
                                        stripeAltColor = if (left < 0) DangerRed.copy(alpha = 0.75f) else activeTheme.stripeB,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "日额度 ¥${money(q)}  ·  已花 ¥${money(spent)}",
                                            color = Muted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        TextButton(
                                            onClick = { go("预算") },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text("查看本月预算 →", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                    if (q == 0L) {
                                        Text("本月预算已用完", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("最近的账目", fontSize = 18.sp, fontWeight = FontWeight.Black)
                                    if (txns.isNotEmpty()) {
                                        TextButton(onClick = { filter = "全部"; go("流水") }) {
                                            Text("全部 ${txns.size} 笔 →", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        }
                                    }
                                }
                                txns.take(5).forEach { TxnRow(it) { editing = it } }
                                if (txns.isEmpty()) {
                                    HardCard(color = activeTheme.cardAlt) {
                                        Text(
                                            "账本还是空的，点「记一笔」开始吧。",
                                            color = Ink,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "也可以一键填入几笔示例账目，马上体验心情日历、分类统计与「值不值」打分。",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Pill("记一笔", activeTheme.primary) { go("记一笔") }
                                            Pill("填入示例账目体验", Color.White) {
                                                seedDemoTransactions(s, today)
                                                coinBurstTrigger++
                                            }
                                        }
                                    }
                                }
                            }

                            "流水" -> {
                                var search by remember { mutableStateOf("") }
                                val visible = remember(txns, filter, search) {
                                    txns.filter {
                                        it.status != "已忽略" &&
                                            (filter == "全部" || it.status == filter || it.direction == filter || it.source == filter || it.category == filter) &&
                                            (it.merchant.contains(search, true) || it.note.contains(search, true) || money(it.cents).contains(search))
                                    }
                                }
                                val totalOut = remember(visible) { visible.filter { it.direction == "支出" }.sumOf { it.cents } }
                                val totalIn = remember(visible) { visible.filter { it.direction == "收入" || it.direction == "退款" }.sumOf { it.cents } }

                                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                    Field(search, "搜索商户、备注或金额") { search = it }
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val baseFilters = buildList {
                                            add("全部")
                                            add("待确认")
                                            add("支出")
                                            add("收入")
                                            add("自动")
                                            if (filter !in listOf("全部", "待确认", "支出", "收入", "自动")) add(filter)
                                        }
                                        baseFilters.forEach { f ->
                                            val label = if (f == "待确认" && pending.isNotEmpty()) "待确认 ${pending.size}" else f
                                            Pill(label, if (filter == f) activeTheme.primary else Color.White) { filter = f }
                                        }
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("共 ${visible.size} 笔", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        Text(
                                            "支出 ¥${money(totalOut)}  ·  收入 ¥${money(totalIn)}",
                                            fontSize = 12.sp,
                                            color = Ink,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        visible.groupBy { day(it.time) }.forEach { (d, ls) ->
                                            item(key = "header_$d") {
                                                val dayLabel = when (d) {
                                                    today -> "今天 · ${d.monthValue}月${d.dayOfMonth}日"
                                                    today.minusDays(1) -> "昨天 · ${d.monthValue}月${d.dayOfMonth}日"
                                                    else -> d.toString()
                                                }
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Stamp(s.calendarMood(d, ls.sumOf(::netExpense), txns), Modifier.size(30.dp, 22.dp))
                                                    Text("  $dayLabel", fontWeight = FontWeight.Black, fontSize = 13.5.sp)
                                                    Spacer(Modifier.weight(1f))
                                                    Text(
                                                        "净支出 ¥${money(ls.sumOf(::netExpense))}",
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 12.5.sp,
                                                        color = Muted
                                                    )
                                                }
                                            }
                                            items(ls, key = { it.id }) { t ->
                                                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                                                    if (value == SwipeToDismissBoxValue.EndToStart) swipeDelete = t
                                                    if (value == SwipeToDismissBoxValue.StartToEnd) editing = t
                                                    false
                                                })
                                                SwipeToDismissBox(
                                                    dismiss,
                                                    backgroundContent = {
                                                        val remove = dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart
                                                        Box(
                                                            Modifier
                                                                .fillMaxSize()
                                                                .background(if (remove) Coral else Mint, RoundedCornerShape(24.dp))
                                                                .border(2.dp, Ink, RoundedCornerShape(24.dp))
                                                                .padding(horizontal = 20.dp),
                                                            contentAlignment = if (remove) Alignment.CenterEnd else Alignment.CenterStart
                                                        ) {
                                                            Text(if (remove) "删除" else "编辑", fontWeight = FontWeight.Black)
                                                        }
                                                    }
                                                ) {
                                                    TxnRow(t) { editing = t }
                                                }
                                            }
                                        }
                                        if (visible.isEmpty()) {
                                            item {
                                                HardCard(color = Color.White) {
                                                    Text("这里还没有账目", fontWeight = FontWeight.Bold, color = Muted)
                                                    if (filter != "全部" || search.isNotEmpty()) {
                                                        Pill("清除筛选条件", Color.White) { filter = "全部"; search = "" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "记一笔" -> ManualEntry(
                                s = s,
                                finish = { go("首页") },
                                initialDate = entryInitialDate,
                                onSavedCelebration = { coinBurstTrigger++ }
                            )

                            "预算" -> ScrollPage {
                                val (start, end) = s.cycle(today)
                                val totalBudget = s.setting("budget", "300000").toLongOrNull() ?: 300000L
                                val used = txns.filter { day(it.time) >= start && day(it.time) < end && it.status != "已忽略" }
                                    .sumOf { if (it.direction == "支出") it.cents else if (it.direction == "退款") -it.cents else 0L }
                                val remaining = totalBudget - used
                                val daysLeft = ChronoUnit.DAYS.between(today, end).coerceAtLeast(1)
                                val dailyQuota = s.quota(today, txns)
                                val todayLeft = dailyQuota - txns.filter { it.status != "已忽略" && day(it.time) == today }.sumOf(::netExpense)

                                HardCard(color = Color.White) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("本月还能花", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Muted)
                                            AnimatedMoneyText(
                                                cents = remaining,
                                                style = moneyStyle(34.sp),
                                                color = if (remaining >= 0) Ink else DangerRed
                                            )
                                            Text("本期 $start ～ ${end.minusDays(1)}", fontSize = 11.5.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                        Pupu(if (remaining >= 0) "happy" else "sad", Modifier.size(72.dp, 80.dp))
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    StripedProgressBar(
                                        progress = if (totalBudget <= 0) 1f else (used.toFloat() / totalBudget).coerceIn(0f, 1f),
                                        stripeColor = activeTheme.stripeA,
                                        stripeAltColor = activeTheme.stripeB,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("已花 ¥${money(used)}", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        Text("总预算 ¥${money(totalBudget)}", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(activeTheme.canvas, RoundedCornerShape(16.dp))
                                            .border(1.8.dp, Ink, RoundedCornerShape(16.dp))
                                            .padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("$daysLeft 天", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                            Text("剩余天数", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("¥${money(dailyQuota)}", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                            Text("日额度", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("¥${money(todayLeft)}", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                            Text("今天还能花", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                var budget by remember { mutableStateOf(money(totalBudget)) }
                                var startDay by remember { mutableStateOf(s.setting("startDay", "1")) }
                                HardCard {
                                    Text("调整月度总预算", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Field(budget, "月预算（元）", true) { budget = it }
                                    Field(startDay, "每月起始日（1—28）", true) { startDay = it }
                                    Pill("保存预算", activeTheme.primary) {
                                        runCatching {
                                            require(cents(budget) > 0)
                                            require(startDay.toInt() in 1..28)
                                            s.set("budget", cents(budget).toString())
                                            s.set("startDay", startDay)
                                            report = "预算已更新"
                                        }.onFailure { report = "请输入有效预算和 1—28 的起始日" }
                                    }
                                }
                                CategoryBudgetCards(s, txns)
                            }

                            "心情日历" -> CalendarPage(
                                s = s,
                                txns = txns,
                                edit = { editing = it },
                                onAddForDate = { selectedDate ->
                                    entryInitialDate = selectedDate
                                    go("记一笔")
                                }
                            )

                            "统计", "月报" -> StatsPage(
                                s,
                                txns,
                                currentPage == "月报",
                                currentPage == "月报" && a.entryIntent?.getBooleanExtra("monthly", false) == true
                            ) {
                                filter = it; go("流水")
                            }

                            "值不值" -> ScrollPage {
                                val todayEligible = txns.filter {
                                    day(it.time) == today && it.direction == "支出" && it.category != "居住" && it.status != "已忽略" && it.score < 0
                                }.sortedByDescending { it.cents }
                                val recentEligible = remember(txns) {
                                    txns.filter {
                                        day(it.time) >= today.minusDays(7) && it.direction == "支出" && it.category != "居住" && it.status != "已忽略" && it.score < 0
                                    }.sortedByDescending { it.cents }
                                }
                                val candidateList = if (todayEligible.isNotEmpty()) todayEligible else recentEligible
                                val t = candidateList.firstOrNull()
                                var score by remember(t?.id) { mutableFloatStateOf(75f) }
                                val moodState = if (score >= 70f) "happy" else if (score <= 30f) "sad" else "ok"
                                val moodComment = when {
                                    score >= 85f -> "超值！这笔钱花得太开心啦"
                                    score >= 70f -> "挺值的！物有所值"
                                    score >= 40f -> "中规中矩，日常正常开销"
                                    else -> "有点后悔…下次三思而后行"
                                }

                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("这笔钱，花得值不值？", fontSize = 22.sp, fontWeight = FontWeight.Black)
                                    Text(
                                        "滑动或点按标签，实时切换粉噗噗三态反馈",
                                        fontSize = 12.5.sp,
                                        color = Muted,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Pupu(moodState, Modifier.fillMaxWidth().height(210.dp))
                                }

                                if (t == null) {
                                    HardCard {
                                        Text("今天的花销都看过啦，明天再来！", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                        val rated = txns.filter { it.direction == "支出" && it.score >= 0 }
                                        if (rated.isNotEmpty()) {
                                            val happyCount = rated.count { it.score >= 70 }
                                            val regretCount = rated.count { it.score <= 30 }
                                            Text(
                                                "累计已评 ${rated.size} 笔 · 超值 $happyCount 笔 · 后悔 $regretCount 笔",
                                                color = Muted,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Pill("跳过", Color.White) { go("首页") }
                                } else {
                                    HardCard {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            CategoryIconBox(t.category, size = 42.dp, circle = true)
                                            Column(Modifier.weight(1f)) {
                                                Text(t.merchant, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                                Text("${day(t.time)} · ${t.category} · ${t.account.ifBlank { t.source }}", fontSize = 12.sp, color = Muted)
                                            }
                                            Text("¥${money(t.cents)}", fontSize = 28.sp, fontWeight = FontWeight.Black)
                                        }
                                    }

                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                    ) {
                                        Pill("抱抱后悔", if (score <= 30f) Sky else Color.White) { score = 20f }
                                        Pill("中规中矩", if (score in 31f..69f) Yellow else Color.White) { score = 50f }
                                        Pill("举手超值", if (score >= 70f) Pink else Color.White) { score = 90f }
                                    }

                                    HardCard {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(moodComment, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                                            Text("${score.toInt()} 分", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Blue)
                                        }
                                        Slider(value = score, onValueChange = { score = it }, valueRange = 0f..100f)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("有点后悔", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                                            Text("超值", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Pill("记下心情，下一笔", Blue) {
                                            t.score = score.toInt()
                                            s.save(t)
                                            if (score >= 70f) coinBurstTrigger++
                                        }
                                        Pill("跳过", Color.White) { go("首页") }
                                    }
                                }
                            }

                            "分类与规则" -> ClassificationPage(s, rev)
                            "账户" -> ScrollPage { AccountCards(s) }
                            "采样记录" -> SampleRecords(
                                s,
                                rev,
                                onReparse = {
                                    scope.launch {
                                        report = withContext(Dispatchers.IO) {
                                            runCatching { s.reparse() }.getOrElse { "重新解析失败，请检查规则。" }
                                        }
                                    }
                                },
                                onExport = { exportType = "samples"; export.launch("空叶记账-样本.json") }
                            )

                            "数据来源" -> SourceSettings(
                                a,
                                s,
                                rev,
                                onNotify = { system(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                                onSms = { permissions.launch(ledgerPermissions()) },
                                onBattery = { system(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                                onImport = { importBill.launch(arrayOf("*/*")) },
                                onScan = {
                                    scope.launch {
                                        report = withContext(Dispatchers.IO) {
                                            runCatching { "已扫描 ${readSms(a)} 条收件箱短信，仅保存白名单动账短信。" }
                                                .getOrElse { "补读失败，请检查短信读取权限。" }
                                        }
                                    }
                                },
                                onSamples = { go("采样记录") },
                                onAppInfo = {
                                    runCatching {
                                        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + a.packageName).toUri()))
                                    }
                                }
                            )

                            "我的" -> ScrollPage {
                                val activeDays = remember(txns) { txns.map { day(it.time) }.distinct().size }
                                HardCard(color = activeTheme.secondary) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("空叶记账", fontSize = 26.sp, fontWeight = FontWeight.Black)
                                            Text(
                                                "粉噗噗陪你，好好花钱。\nv0.2.0 · 离线记账 · 已记 $activeDays 天",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                        Pupu("happy", Modifier.size(64.dp, 72.dp))
                                    }
                                }

                                // 4-Theme Selector Card
                                ThemeSelectorCard(s)

                                // 3-Stamp-Group Selector Card
                                StampGroupSelectorCard(s)

                                listOf(
                                    "预算" to "总预算、起始日与分类限额",
                                    "分类与规则" to "管理分类颜色与商户自动归类关键词",
                                    "账户" to "现金、微信、支付宝及银行卡尾号",
                                    "数据来源" to "付款通知、银行短信与微信/支付宝账单导入",
                                    "月报" to "查看本月与上月收支构成及心情统计"
                                ).forEach { (p, desc) ->
                                    HardCard(modifier = Modifier.clickable { go(p) }) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text("$p  →", fontWeight = FontWeight.Black, fontSize = 16.sp)
                                                Text(desc, fontSize = 12.sp, color = Muted)
                                            }
                                        }
                                    }
                                }

                                MoodThreshold(s)

                                HardCard {
                                    Toggle("记账提醒通知", s.setting("reminders", "true") == "true") {
                                        s.set("reminders", it.toString())
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Pill("查看新手引导", Color.White) { go("欢迎") }
                                        Pill("重新播放开屏动画", Color.White) { showSplash = true }
                                    }
                                }

                                MoreSettings(
                                    a,
                                    s,
                                    { importRules.launch(arrayOf("*/*")) },
                                    { exportType = "rules"; export.launch("空叶记账-解析规则.json") }
                                )

                                HardCard {
                                    Text("数据备份与恢复", fontWeight = FontWeight.Black, fontSize = 17.sp)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Pill("导出完整备份", activeTheme.primary) { exportType = "backup"; export.launch("空叶记账-${today}.json") }
                                        Pill("恢复备份（按记录合并）", Color.White) { restore.launch(arrayOf("*/*")) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("数据保存在应用私有目录。卸载前请导出备份。当前版本不含 WebDAV。", color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Coin Fountain Celebration Overlay on Save / Confirm
            CoinBurstOverlay(trigger = coinBurstTrigger)

            // Animated Splash Screen Overlay on Launch
            AnimatedVisibility(
                visible = showSplash,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(240)) + scaleOut(targetScale = 1.05f, animationSpec = tween(240))
            ) {
                SplashScreenOverlay { showSplash = false }
            }
        }

        if (editing != null) {
            val t = editing!!
            ModalBottomSheet(
                onDismissRequest = { editing = null },
                containerColor = activeTheme.canvas,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.93f)) {
                    Row(
                        Modifier.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (t.status == "待确认") "噗噗抓到一笔账 · 请确认" else "账目详情",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "${t.source} · ${t.status}" + if (t.events.size > 1) " · 合并 ${t.events.size} 条来源" else "",
                                color = Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Pupu("happy", Modifier.size(56.dp))
                    }
                    Box(Modifier.weight(1f).imePadding()) {
                        Editor(
                            t,
                            s,
                            onSave = {
                                val wasPending = t.status == "待确认" && it.status == "已入账"
                                s.save(it)
                                editing = null
                                if (wasPending) coinBurstTrigger++
                            },
                            onCancel = { editing = null },
                            onDelete = { editing = null; remove(t) }
                        )
                    }
                }
            }
        }

        swipeDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { swipeDelete = null },
                containerColor = activeTheme.canvas,
                title = { Text("删除这笔账目？", fontWeight = FontWeight.Black) },
                text = { Text("删除后 5 秒内可撤销。") },
                confirmButton = {
                    TextButton(onClick = { remove(item); swipeDelete = null }) {
                        Text("删除", color = DangerRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { swipeDelete = null }) { Text("取消", color = Ink) }
                }
            )
        }

        if (report.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { report = "" },
                containerColor = activeTheme.canvas,
                title = { Text("空叶记账", fontWeight = FontWeight.Black) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(report) } },
                confirmButton = {
                    TextButton(onClick = { report = "" }) { Text("知道了", color = Ink, fontWeight = FontWeight.Bold) }
                }
            )
        }
    }
}

@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalLedgerTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = theme.primary,
                checkedBorderColor = Ink,
                uncheckedThumbColor = Ink,
                uncheckedTrackColor = theme.canvas,
                uncheckedBorderColor = Ink
            )
        )
    }
}

@Composable
fun PermissionCards(s: Store, notification: () -> Unit, sms: () -> Unit, battery: () -> Unit) {
    AuthorizationCards(s, notification, sms, battery)
}

@Composable
fun TxnRow(t: Txn, onClick: () -> Unit) {
    val theme = LocalLedgerTheme.current
    val isIncome = t.direction == "收入" || t.direction == "退款"
    val sign = when (t.direction) {
        "收入", "退款" -> "+"
        "转账" -> "↔"
        else -> "−"
    }
    val timeStr = Instant.ofEpochMilli(t.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    HardCard(
        Modifier.clickable(onClick = onClick),
        color = Color.White
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryIconBox(t.category, size = 40.dp, circle = false)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        t.merchant,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (t.status == "待确认") {
                        Box(
                            Modifier
                                .background(theme.primary, RoundedCornerShape(50))
                                .border(1.5.dp, Ink, RoundedCornerShape(50))
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                        ) {
                            Text("待确认", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Ink)
                        }
                    }
                }
                val metaParts = buildList {
                    add(t.category)
                    add(t.source)
                    if (t.account.isNotBlank()) add(t.account)
                    add(if (t.status == "待确认") "待确认" else timeStr)
                    if (t.note.isNotBlank()) add(t.note)
                }
                Text(
                    metaParts.joinToString(" · "),
                    fontSize = 12.sp,
                    color = Muted,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "$sign¥${money(t.cents)}",
                style = moneyStyle(16.sp),
                color = if (isIncome) GreenIncome else Ink
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Editor(original: Txn?, s: Store, onSave: (Txn) -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null) {
    val theme = LocalLedgerTheme.current
    var amount by remember { mutableStateOf(original?.let { money(it.cents) } ?: "") }
    var merchant by remember { mutableStateOf(original?.merchant ?: "") }
    var cat by remember { mutableStateOf(original?.category ?: "餐饮") }
    var direction by remember { mutableStateOf(original?.direction ?: "支出") }
    var note by remember { mutableStateOf(original?.note ?: "") }
    var account by remember { mutableStateOf(original?.account?.ifBlank { "微信" } ?: "微信") }
    var time by remember {
        mutableStateOf(
            Instant.ofEpochMilli(original?.time ?: System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        )
    }
    var learn by remember { mutableStateOf(original?.status == "待确认") }
    var error by remember { mutableStateOf("") }

    ScrollPage {
        Chips(listOf("支出", "收入", "转账", "退款"), direction) { direction = it }
        HardCard(color = theme.primary) {
            Field(amount, "金额（元）", true) { amount = it }
        }
        Field(merchant, "商户 / 交易名称") { merchant = it }
        Chips(s.categoryChoices().map { it.name }, cat) { cat = it }
        Field(time, "时间 yyyy-MM-dd HH:mm") { time = it }
        Chips(s.accounts(), account) { account = it }
        Field(account, "账户（银行卡仅填后四位）") { account = it }
        Field(note, "备注 / 标签") { note = it }
        if (original != null) Toggle("以后这个商户都这样记", learn) { learn = it }
        if (error.isNotEmpty()) Text(error, color = DangerRed, fontWeight = FontWeight.Bold)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("记下来", theme.primary) {
                runCatching {
                    val c = cents(amount)
                    require(c > 0) { "金额必须大于 0" }
                    require(merchant.isNotBlank()) { "请填写商户" }
                    val ts = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val t = original?.copy(events = original.events.toMutableList())
                        ?: Txn(cents = c, merchant = merchant, category = cat)
                    t.cents = c; t.merchant = merchant.trim(); t.category = cat; t.direction = direction; t.note = note.trim()
                    require(!Regex("\\d{5,}").containsMatchIn(account)) { "银行卡账户只填后四位" }
                    t.account = account.trim(); t.time = ts; t.status = "已入账"
                    s.linkRefund(t)
                    if (learn) s.rule(merchant.trim(), cat, true)
                    onSave(t)
                }.onFailure { error = it.message ?: "请检查输入" }
            }
            Pill("取消", Color.White, onClick = onCancel)
            if (original != null) {
                Pill("先不记", Color.White) { original.status = "已忽略"; onSave(original) }
            }
            if (onDelete != null) Pill("删除这笔", Coral, onClick = onDelete)
        }
        if (original != null && original.events.size > 1) {
            Pill("拆开合并的记录", Color.White) { s.split(original); onCancel() }
        }
        if (original != null && original.events.isNotEmpty()) {
            HardCard {
                Text("识别来源原文", fontWeight = FontWeight.Black)
                original.events.forEach { eid ->
                    s.db.records().find(eid)?.payload?.let { org.json.JSONObject(it) }?.let {
                        Text(
                            "${it.optString("sender")}\n${it.optString("title")}\n${it.optString("body")}",
                            fontSize = 12.sp,
                            color = Muted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryBubble(cat: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(
        if (pressed) 2.dp else 0.dp,
        spring(dampingRatio = 0.55f, stiffness = 850f),
        label = "bub_shift"
    )
    val jellyScale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.44f, stiffness = 780f),
        label = "bub_jelly"
    )
    Column(
        modifier
            .width(88.dp)
            .scale(jellyScale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(54.dp).padding(end = 2.dp, bottom = 2.dp)) {
            Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, CircleShape))
            Box(
                Modifier
                    .matchParentSize()
                    .offset(shift, shift)
                    .background(catColor(cat), CircleShape)
                    .border(2.5.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CategoryIcon(cat, Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(cat, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Ink)
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            textAlign = TextAlign.Center
        )
    }
}
