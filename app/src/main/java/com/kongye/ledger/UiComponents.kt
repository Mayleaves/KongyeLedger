package com.kongye.ledger

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

val Coral = Color(0xfff4a58e)
val Sky = Color(0xff8ccae6)
val Pink = Color(0xfff7b3c2)
val Navy = Color(0xff3f4a6e)
val Mint = Color(0xffc9e7c3)
val Lavender = Color(0xffddd0f4)
val GreenIncome = Color(0xff3f9a5b)
val DangerRed = Color(0xffa33a37)

data class LedgerThemePalette(
    val key: String,
    val title: String,
    val subtitle: String,
    val canvas: Color,
    val cardAlt: Color,
    val primary: Color,
    val secondary: Color,
    val stripeA: Color,
    val stripeB: Color,
    val swatches: List<Color>
)

val ledgerThemes = listOf(
    LedgerThemePalette(
        key = "cream",
        title = "经典暖米",
        subtitle = "手账纸感暖米底 + 活力暖金与珊瑚粉",
        canvas = Color(0xfff7f0e3),
        cardAlt = Color(0xfffffaf2),
        primary = Color(0xfff6c945),
        secondary = Color(0xfff7b3c2),
        stripeA = Color(0xfff7b3c2),
        stripeB = Color(0xfff39bb0),
        swatches = listOf(Color(0xfff7f0e3), Color(0xfff6c945), Color(0xfff7b3c2), Color(0xff33281f))
    ),
    LedgerThemePalette(
        key = "peach",
        title = "草莓蜜桃",
        subtitle = "粉噗噗同款柔粉底 + 蜜桃珊瑚与奶油黄",
        canvas = Color(0xfffff2ef),
        cardAlt = Color(0xfffff9f7),
        primary = Color(0xffffb3c1),
        secondary = Color(0xfffbe39a),
        stripeA = Color(0xffff9ebb),
        stripeB = Color(0xfff282a2),
        swatches = listOf(Color(0xfffff2ef), Color(0xffffb3c1), Color(0xfffbe39a), Color(0xffda746a))
    ),
    LedgerThemePalette(
        key = "mint",
        title = "晴空薄荷",
        subtitle = "清爽盐系薄荷底 + 晴空蓝与鼠尾草绿",
        canvas = Color(0xffeef7f2),
        cardAlt = Color(0xfff7fcf9),
        primary = Color(0xff98dfa6),
        secondary = Color(0xff8ccae6),
        stripeA = Color(0xff8ccae6),
        stripeB = Color(0xff67b3d6),
        swatches = listOf(Color(0xffeef7f2), Color(0xff98dfa6), Color(0xff8ccae6), Color(0xff33281f))
    ),
    LedgerThemePalette(
        key = "taro",
        title = "奶盐香芋",
        subtitle = "温柔香芋云朵底 + 薰衣草紫与暖杏黄",
        canvas = Color(0xfff4f0fa),
        cardAlt = Color(0xfffaf8fd),
        primary = Color(0xffd7c2f7),
        secondary = Color(0xfffbe39a),
        stripeA = Color(0xffd7c2f7),
        stripeB = Color(0xffbfa3ec),
        swatches = listOf(Color(0xfff4f0fa), Color(0xffd7c2f7), Color(0xfffbe39a), Color(0xff3f4a6e))
    )
)

fun themeByKey(key: String): LedgerThemePalette =
    ledgerThemes.firstOrNull { it.key == key } ?: ledgerThemes.first()

val LocalStampGroup = compositionLocalOf { "g2" }
val LocalLedgerTheme = compositionLocalOf { ledgerThemes.first() }

data class StampGroupInfo(
    val key: String,
    val title: String,
    val subtitle: String,
    val happyName: String,
    val okName: String,
    val sadName: String
)

val stampGroups = listOf(
    StampGroupInfo("g1", "第 1 组 · 经典心星泪", "圆润团子脸 + 头顶单心 / 暖金星 / 大水滴", "省钱笑脸", "日常平稳", "超支委屈"),
    StampGroupInfo("g2", "第 2 组 · 圆润饱满表情章", "弯眼甜笑豆嘴 / 呆萌猫嘴 / 八字眉瘪嘴冷汗", "甜笑团子猪", "乖巧团子猪", "委屈团子猪"),
    StampGroupInfo("g3", "第 3 组 · 记账小剧场", "星光眼顶金币 / 呆萌眼顶空叶嫩芽 / 八字眉顶雨云", "金币入罐", "空叶日常", "阴雨超支")
)

fun stampDrawableRes(state: String, group: String): Int = when (group) {
    "g1" -> when (state) {
        "sad" -> R.drawable.stamp_g1_sad
        "ok" -> R.drawable.stamp_g1_ok
        else -> R.drawable.stamp_g1_happy
    }
    "g3" -> when (state) {
        "sad" -> R.drawable.stamp_c_sad
        "ok" -> R.drawable.stamp_c_ok
        else -> R.drawable.stamp_c_happy
    }
    else -> when (state) {
        "sad" -> R.drawable.stamp_a_sad
        "ok" -> R.drawable.stamp_a_ok
        else -> R.drawable.stamp_a_happy
    }
}

fun moodLabel(state: String, group: String = "g2"): String {
    val info = stampGroups.firstOrNull { it.key == group } ?: stampGroups[1]
    return when (state) {
        "sad" -> info.sadName
        "ok" -> info.okName
        else -> info.happyName
    }
}

@Composable
fun AnimatedMoneyText(
    cents: Long,
    style: TextStyle,
    color: Color = Ink,
    prefix: String = "¥"
) {
    val animatedCents by animateIntAsState(
        targetValue = cents.coerceIn(-999_999_99L, 999_999_99L).toInt(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "money_roll"
    )
    Text(
        text = "$prefix${money(animatedCents.toLong())}",
        style = style,
        color = color
    )
}

@Composable
fun AnimatedPupu(
    state: String,
    modifier: Modifier = Modifier,
    bobbing: Boolean = true
) {
    val infinite = rememberInfiniteTransition(label = "pupu_idle")
    val bobOffset by infinite.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pupu_bob"
    )
    val breathScale by infinite.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pupu_scale"
    )
    var bounceTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) { bounceTrigger++ }
    val statePop by animateFloatAsState(
        targetValue = if (bounceTrigger % 2 == 0) 1f else 1.001f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 620f),
        label = "pupu_pop"
    )

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(dampingRatio = 0.56f, stiffness = 650f)
            ) + fadeIn(tween(150))) togetherWith
                (scaleOut(targetScale = 0.90f, animationSpec = tween(130)) + fadeOut(tween(120)))
        },
        modifier = modifier.then(
            if (bobbing) Modifier.offset(y = bobOffset.dp).scale(breathScale * statePop) else Modifier
        ),
        label = "pupu_switch"
    ) { target ->
        Image(
            painter = painterResource(
                when (target) {
                    "sad" -> R.drawable.v8_sad
                    "ok" -> R.drawable.v8_ok
                    else -> R.drawable.v8_happy
                }
            ),
            contentDescription = "粉噗噗",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun CoinBurstOverlay(
    trigger: Int,
    modifier: Modifier = Modifier
) {
    if (trigger <= 0) return
    val anim = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        anim.snapTo(0f)
        anim.animateTo(1f, animationSpec = tween(680, easing = FastOutSlowInEasing))
    }
    val p = anim.value
    if (p >= 0.99f) return

    val particles = remember {
        listOf(
            Triple(-110f, -150f, 0.95f),
            Triple(-58f, -195f, 1.1f),
            Triple(0f, -215f, 1.15f),
            Triple(62f, -190f, 1.05f),
            Triple(112f, -145f, 0.9f),
            Triple(-82f, -105f, 0.85f),
            Triple(86f, -110f, 0.85f)
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        particles.forEach { (dx, peakY, sz) ->
            val x = dx * p
            val y = peakY * (1f - (2f * p - 1f) * (2f * p - 1f)) + 60f * p
            val alpha = (1f - p * 0.85f).coerceIn(0f, 1f)
            val scale = (0.6f + 0.55f * kotlin.math.sin(p * Math.PI).toFloat()) * sz
            CoinBadge(
                modifier = Modifier
                    .offset(x = x.dp, y = y.dp)
                    .scale(scale)
                    .graphicsLayer {
                        this.alpha = alpha
                        rotationZ = p * dx * 1.4f
                    }
                    .size(34.dp)
            )
        }
    }
}

@Composable
fun SplashScreenOverlay(
    modifier: Modifier = Modifier,
    readyToEnter: Boolean = true,
    loadProgress: Float = 1f,
    onEntranceFinished: () -> Unit = {},
    onSkip: (() -> Unit)? = null,
    onFinished: () -> Unit
) {
    val entrance = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val startLedger by rememberUpdatedState(onEntranceFinished)
    val finish by rememberUpdatedState(onFinished)
    var introSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(520, easing = LinearEasing))
        startLedger()
        introSettled = true
    }
    val canDismiss = introSettled && readyToEnter
    val progressTarget = if (canDismiss) loadProgress else minOf(loadProgress, .94f)
    LaunchedEffect(progressTarget, canDismiss) {
        progress.animateTo(progressTarget, tween(if (canDismiss) 180 else 210, easing = FastOutSlowInEasing))
        if (canDismiss) {
            withFrameNanos { }
            finish()
        }
    }
    val sheen = if (!readyToEnter && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "loading_sheen").animateFloat(
            initialValue = -.45f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
            label = "loading_sheen_position"
        )
    } else null

    val background = colorResource(R.color.ledger_launch_background)
    val startScale = dimensionResource(R.dimen.ledger_splash_mascot_height) / 188.dp
    fun reveal(from: Float) = FastOutSlowInEasing.transform(((entrance.value - from) / (1f - from)).coerceIn(0f, 1f))
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .then(modifier)
            .clickable(indication = null, interactionSource = null, onClickLabel = "进入账本") { (onSkip ?: finish)() },
        contentAlignment = Alignment.Center
    ) {
        val rise = (maxHeight * .085f).coerceIn(38.dp, 72.dp)
        Canvas(Modifier.fillMaxSize()) {
            val glow = reveal(.12f)
            val center = Offset(size.width / 2f, size.height / 2f - rise.toPx() + 12.dp.toPx())
            val radius = 125.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xfffcc0b3).copy(alpha = glow * .36f),
                    .48f to Color(0xffffe494).copy(alpha = glow * .22f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
            drawFourPointStar(Offset(size.width * .16f, size.height * .23f), 9.dp.toPx(),
                Color(0xfffcd65c).copy(alpha = glow), Color(0xffe5a124).copy(alpha = glow))
            drawFourPointStar(Offset(size.width * .85f, size.height * .29f), 7.dp.toPx(),
                Color(0xfffcc0b3).copy(alpha = glow), Color(0xffda746a).copy(alpha = glow))
        }

        CoinBadge(Modifier.offset(x = 62.dp, y = -rise - 77.dp).size(40.dp).graphicsLayer {
            val fraction = reveal(.22f)
            alpha = fraction
            translationY = -66.dp.toPx() * (1f - fraction)
            rotationZ = -25f * (1f - fraction)
            scaleX = .55f + .45f * fraction
            scaleY = scaleX
        })
        Image(
            painter = painterResource(R.drawable.v8_happy),
            contentDescription = null,
            modifier = Modifier.size(188.dp).graphicsLayer {
                val fraction = reveal(0f)
                translationY = -rise.toPx() * fraction
                scaleX = startScale + (1f - startScale) * fraction
                scaleY = scaleX
            }
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = maxHeight / 2 - rise + 104.dp)
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    val fraction = reveal(.23f)
                    alpha = fraction
                    translationY = 8.dp.toPx() * (1f - fraction)
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("空叶记账", color = Color(0xff3d3126), fontSize = 28.sp,
                lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text("付钱的那一刻，噗噗替你记下", color = Color(0xff968471), fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(17.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(R.drawable.stamp_a_happy, R.drawable.stamp_a_ok, R.drawable.stamp_a_sad).forEachIndexed { index, stamp ->
                    Image(
                        painter = painterResource(stamp),
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer {
                            val fraction = reveal(.31f + index * .12f)
                            alpha = fraction
                            scaleX = .67f + .33f * fraction
                            scaleY = scaleX
                        }.background(Color.White, RoundedCornerShape(16.dp))
                            .padding(horizontal = 11.dp, vertical = 6.dp).size(36.dp, 26.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Canvas(Modifier.width(168.dp).height(7.dp)) {
                val corners = CornerRadius(size.height / 2)
                val fillWidth = size.width * progress.value
                drawRoundRect(Color(0xffeee8dc), cornerRadius = corners)
                if (fillWidth > 0f) {
                    val fillSize = Size(fillWidth, size.height)
                    val fillCorners = CornerRadius(minOf(size.height, fillWidth) / 2)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(Color(0xfff9b4b8), Color(0xfff3969b)), endX = fillWidth),
                        size = fillSize,
                        cornerRadius = fillCorners
                    )
                    sheen?.let {
                        val center = fillWidth * it.value
                        val halfWidth = fillWidth * .34f
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color(0x7affffff), Color.Transparent),
                                startX = center - halfWidth,
                                endX = center + halfWidth
                            ),
                            size = fillSize,
                            cornerRadius = fillCorners
                        )
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
            Text("轻触任意处进入账本", color = Color(0xff968471), fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}
private fun DrawScope.drawFourPointStar(center: Offset, radius: Float, fill: Color, stroke: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.35f, center.y - radius * 0.35f)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + radius * 0.35f, center.y + radius * 0.35f)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius * 0.35f, center.y + radius * 0.35f)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - radius * 0.35f, center.y - radius * 0.35f)
        close()
    }
    drawPath(path, fill)
    drawPath(path, stroke, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
}

@Composable
fun CoinBadge(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 2.dp.toPx()
            drawCircle(Yellow, radius = r)
            drawCircle(Ink, radius = r, style = Stroke(2.5.dp.toPx()))
            drawCircle(Color(0xffffe278), radius = r * 0.72f)
            drawCircle(Color(0xffd99b16), radius = r * 0.72f, style = Stroke(1.5.dp.toPx()))
        }
        Text("¥", color = Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
fun StripedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    stripeColor: Color = LocalLedgerTheme.current.stripeA,
    stripeAltColor: Color = LocalLedgerTheme.current.stripeB,
    trackColor: Color = LocalLedgerTheme.current.canvas
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "striped_progress"
    )
    val shape = RoundedCornerShape(50)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(shape)
            .background(trackColor, shape)
            .border(2.2.dp, Ink, shape)
    ) {
        val fillWidth = size.width * animated
        if (fillWidth > 0.5f) {
            drawRect(stripeColor, size = Size(fillWidth, size.height))
            val stripeStep = 12.dp.toPx()
            val stripeWidth = 5.dp.toPx()
            var x = -size.height
            while (x < fillWidth + size.height) {
                val path = Path().apply {
                    moveTo(x, size.height)
                    lineTo(x + stripeWidth, size.height)
                    lineTo(x + size.height + stripeWidth, 0f)
                    lineTo(x + size.height, 0f)
                    close()
                }
                if (x + size.height < fillWidth) {
                    drawPath(path, stripeAltColor)
                }
                x += stripeStep
            }
            if (animated < 0.995f) {
                drawLine(
                    color = Ink,
                    start = Offset(fillWidth, 0f),
                    end = Offset(fillWidth, size.height),
                    strokeWidth = 2.2.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun IconBtn(
    icon: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(
        if (pressed) 2.dp else 0.dp,
        spring(dampingRatio = 0.55f, stiffness = 800f),
        label = "icon_btn_shift"
    )
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.52f, stiffness = 750f),
        label = "icon_btn_scale"
    )
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.size(42.dp).scale(scale).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(Ink, shape))
        Box(
            Modifier
                .matchParentSize()
                .offset(shift, shift)
                .background(color, shape)
                .border(2.2.dp, Ink, shape)
                .clip(shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(icon, Modifier.size(20.dp), contentDescription = contentDescription)
        }
    }
}

@Composable
fun CategoryIconBox(
    category: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    circle: Boolean = false
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .background(catColor(category), shape)
            .border(2.2.dp, Ink, shape),
        contentAlignment = Alignment.Center
    ) {
        CategoryIcon(category, Modifier.size(size * 0.54f))
    }
}

@Composable
fun CategoryIcon(category: String, modifier: Modifier = Modifier, tint: Color = Ink) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (size.minDimension * 0.105f).coerceAtLeast(1.8.dp.toPx())
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (category) {
            "餐饮" -> {
                val cup = Path().apply {
                    moveTo(w * 0.18f, h * 0.38f)
                    lineTo(w * 0.72f, h * 0.38f)
                    lineTo(w * 0.66f, h * 0.76f)
                    quadraticTo(w * 0.45f, h * 0.88f, w * 0.24f, h * 0.76f)
                    close()
                }
                drawPath(cup, tint, style = stroke)
                drawArc(
                    color = tint,
                    startAngle = -70f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(w * 0.64f, h * 0.43f),
                    size = Size(w * 0.22f, h * 0.24f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.36f, h * 0.14f), Offset(w * 0.36f, h * 0.26f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.54f, h * 0.14f), Offset(w * 0.54f, h * 0.26f), sw, StrokeCap.Round)
            }
            "交通" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.20f, h * 0.14f),
                    size = Size(w * 0.60f, h * 0.58f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.20f, h * 0.46f), Offset(w * 0.80f, h * 0.46f), sw, StrokeCap.Round)
                drawCircle(tint, radius = sw * 0.65f, center = Offset(w * 0.36f, h * 0.59f))
                drawCircle(tint, radius = sw * 0.65f, center = Offset(w * 0.64f, h * 0.59f))
                drawLine(tint, Offset(w * 0.34f, h * 0.72f), Offset(w * 0.26f, h * 0.88f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.72f), Offset(w * 0.74f, h * 0.88f), sw, StrokeCap.Round)
            }
            "购物" -> {
                val cart = Path().apply {
                    moveTo(w * 0.12f, h * 0.20f)
                    lineTo(w * 0.24f, h * 0.20f)
                    lineTo(w * 0.34f, h * 0.64f)
                    lineTo(w * 0.76f, h * 0.64f)
                    lineTo(w * 0.85f, h * 0.32f)
                    lineTo(w * 0.28f, h * 0.32f)
                }
                drawPath(cart, tint, style = stroke)
                drawCircle(tint, radius = sw * 0.75f, center = Offset(w * 0.38f, h * 0.80f))
                drawCircle(tint, radius = sw * 0.75f, center = Offset(w * 0.72f, h * 0.80f))
            }
            "居住" -> {
                val house = Path().apply {
                    moveTo(w * 0.16f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.46f)
                    lineTo(w * 0.84f, h * 0.84f)
                    lineTo(w * 0.16f, h * 0.84f)
                    close()
                }
                drawPath(house, tint, style = stroke)
                val door = Path().apply {
                    moveTo(w * 0.41f, h * 0.84f)
                    lineTo(w * 0.41f, h * 0.58f)
                    lineTo(w * 0.59f, h * 0.58f)
                    lineTo(w * 0.59f, h * 0.84f)
                }
                drawPath(door, tint, style = stroke)
            }
            "娱乐" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.28f),
                    size = Size(w * 0.76f, h * 0.46f),
                    cornerRadius = CornerRadius(w * 0.22f, w * 0.22f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.33f, h * 0.43f), Offset(w * 0.33f, h * 0.59f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.25f, h * 0.51f), Offset(w * 0.41f, h * 0.51f), sw, StrokeCap.Round)
                drawCircle(tint, radius = sw * 0.65f, center = Offset(w * 0.64f, h * 0.46f))
                drawCircle(tint, radius = sw * 0.65f, center = Offset(w * 0.73f, h * 0.56f))
            }
            "医疗" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.16f, h * 0.22f),
                    size = Size(w * 0.68f, h * 0.60f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.66f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.52f), Offset(w * 0.64f, h * 0.52f), sw, StrokeCap.Round)
            }
            "学习" -> {
                val book = Path().apply {
                    moveTo(w * 0.14f, h * 0.24f)
                    quadraticTo(w * 0.34f, h * 0.20f, w * 0.50f, h * 0.30f)
                    quadraticTo(w * 0.66f, h * 0.20f, w * 0.86f, h * 0.24f)
                    lineTo(w * 0.86f, h * 0.76f)
                    quadraticTo(w * 0.66f, h * 0.72f, w * 0.50f, h * 0.82f)
                    quadraticTo(w * 0.34f, h * 0.72f, w * 0.14f, h * 0.76f)
                    close()
                }
                drawPath(book, tint, style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.80f), sw, StrokeCap.Round)
            }
            "人情" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.18f, h * 0.38f),
                    size = Size(w * 0.64f, h * 0.46f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.14f, h * 0.38f), Offset(w * 0.86f, h * 0.38f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.84f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.40f, h * 0.27f), style = stroke)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.60f, h * 0.27f), style = stroke)
            }
            "收入" -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                drawLine(tint, Offset(w * 0.38f, h * 0.34f), Offset(w * 0.50f, h * 0.48f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, h * 0.34f), Offset(w * 0.50f, h * 0.48f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.50f), Offset(w * 0.62f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.60f), Offset(w * 0.62f, h * 0.60f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.48f), Offset(w * 0.50f, h * 0.70f), sw, StrokeCap.Round)
            }
            else -> {
                val star = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.58f, h * 0.42f)
                    lineTo(w * 0.84f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.58f)
                    lineTo(w * 0.50f, h * 0.84f)
                    lineTo(w * 0.42f, h * 0.58f)
                    lineTo(w * 0.16f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.42f)
                    close()
                }
                drawPath(star, tint, style = stroke)
            }
        }
    }
}

@Composable
fun AppIcon(name: String, modifier: Modifier = Modifier, tint: Color = Ink, contentDescription: String? = null) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (size.minDimension * 0.11f).coerceAtLeast(2.dp.toPx())
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (name) {
            "calendar" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.15f, h * 0.22f),
                    size = Size(w * 0.70f, h * 0.62f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = stroke
                )
                drawLine(tint, Offset(w * 0.15f, h * 0.42f), Offset(w * 0.85f, h * 0.42f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.13f), Offset(w * 0.34f, h * 0.29f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.13f), Offset(w * 0.66f, h * 0.29f), sw, StrokeCap.Round)
            }
            "back", "left" -> {
                val p = Path().apply {
                    moveTo(w * 0.62f, h * 0.22f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.78f)
                }
                drawPath(p, tint, style = stroke)
            }
            "right" -> {
                val p = Path().apply {
                    moveTo(w * 0.38f, h * 0.22f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.38f, h * 0.78f)
                }
                drawPath(p, tint, style = stroke)
            }
            "close" -> {
                drawLine(tint, Offset(w * 0.26f, h * 0.26f), Offset(w * 0.74f, h * 0.74f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.74f, h * 0.26f), Offset(w * 0.26f, h * 0.74f), sw, StrokeCap.Round)
            }
            "plus" -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.80f), sw * 1.15f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), sw * 1.15f, StrokeCap.Round)
            }
            "search" -> {
                drawCircle(tint, radius = w * 0.26f, center = Offset(w * 0.44f, h * 0.44f), style = stroke)
                drawLine(tint, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.82f, h * 0.82f), sw, StrokeCap.Round)
            }
            "home" -> {
                val p = Path().apply {
                    moveTo(w * 0.16f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.18f)
                    lineTo(w * 0.84f, h * 0.48f)
                    lineTo(w * 0.84f, h * 0.82f)
                    lineTo(w * 0.16f, h * 0.82f)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }
            "list" -> {
                drawLine(tint, Offset(w * 0.20f, h * 0.28f), Offset(w * 0.80f, h * 0.28f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.72f), Offset(w * 0.58f, h * 0.72f), sw, StrokeCap.Round)
            }
            "chart" -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.80f), Offset(w * 0.22f, h * 0.46f), sw * 1.1f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.80f), Offset(w * 0.50f, h * 0.22f), sw * 1.1f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.78f, h * 0.80f), Offset(w * 0.78f, h * 0.58f), sw * 1.1f, StrokeCap.Round)
            }
            "user", "gear" -> {
                drawCircle(tint, radius = w * 0.15f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                for (angle in 0 until 360 step 45) {
                    rotate(angle.toFloat(), Offset(w * 0.50f, h * 0.50f)) {
                        drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.24f), sw, StrokeCap.Round)
                    }
                }
            }
            "lock" -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.44f),
                    size = Size(w * 0.56f, h * 0.40f),
                    cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                    style = stroke
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.20f),
                    size = Size(w * 0.36f, h * 0.42f),
                    style = stroke
                )
            }
            "check" -> {
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.72f)
                    lineTo(w * 0.78f, h * 0.28f)
                }
                drawPath(p, tint, style = stroke)
            }
        }
    }
}

@Composable
fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.White
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .background(containerColor, RoundedCornerShape(18.dp))
                .border(2.2.dp, Ink, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                textAlign = TextAlign.Center
            )
        }
        Canvas(Modifier.size(14.dp, 7.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, containerColor)
            drawLine(Ink, Offset(0f, 0f), Offset(size.width / 2f, size.height), 2.2.dp.toPx(), StrokeCap.Round)
            drawLine(Ink, Offset(size.width / 2f, size.height), Offset(size.width, 0f), 2.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun ThemeSelectorCard(s: Store) {
    val curTheme = LocalLedgerTheme.current
    HardCard {
        Text("主题配色（4 套风格）", fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text("支持在经典暖米、草莓蜜桃、晴空薄荷与奶盐香芋间自由切换。", fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ledgerThemes.forEach { palette ->
                val isSelected = palette.key == curTheme.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(palette.canvas, RoundedCornerShape(18.dp))
                        .border(
                            if (isSelected) 2.5.dp else 1.8.dp,
                            if (isSelected) Blue else Ink,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable { s.set("theme", palette.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(palette.title, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink)
                            if (isSelected) {
                                Box(
                                    Modifier
                                        .background(palette.primary, RoundedCornerShape(50))
                                        .border(1.4.dp, Ink, RoundedCornerShape(50))
                                        .padding(horizontal = 7.dp, vertical = 1.dp)
                                ) {
                                    Text("使用中", fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = Ink)
                                }
                            }
                        }
                        Text(palette.subtitle, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        palette.swatches.forEach { sw ->
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .background(sw, CircleShape)
                                    .border(1.5.dp, Ink, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}
