package com.dslauncher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dslauncher.service.ApiService
import com.dslauncher.ui.FeaturesScreen
import com.dslauncher.ui.LauncherScreen
import com.dslauncher.ui.account.AccountScreen
import com.dslauncher.ui.account.QwenAccountScreen
import com.dslauncher.ui.login.QwenLoginScreen
import com.dslauncher.ui.login.DeepSeekLoginActivity
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 底部导航项。 */
private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("启动", Icons.Default.PlayArrow),
    Tab("功能", Icons.Default.Build),
    Tab("账号", Icons.Default.AccountCircle)
)

/**
 * 页面结构（横向 Pager，共 3 页，底部悬浮滑块切换）：
 *
 *   0 启动  — 端口 / 密钥 / API 地址 / 启动与停止 / 关于软件（含作者 Gnuning）
 *   1 功能  — 深度思考 / 防撤回 / 局域网 / 开发者模式 / 内网穿透
 *   2 账号  — 账号池管理（取号策略、配额、导入、校验状态）
 *
 * 底部那枚圆润悬浮滑块：单击任意分段直接切换，长按后左右拖动也能切页。
 * 登录页是覆盖层（不在 Pager 内），从账号页右上角「+」进入。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DeepSeekApiLauncherTheme {
                val context = LocalContext.current
                val app = App.from(context)
                val scope = rememberCoroutineScope()
                val pagerState = rememberPagerState(pageCount = { TABS.size })
                var showQwenLogin by remember { mutableStateOf(false) }
                // 账号页当前展示的池：DeepSeek / Qwen（启动页入口卡会切换它）
                var qwenAccountsPage by remember { mutableStateOf(false) }
                // 免责声明：首次启动必须同意后才能使用
                var showDisclaimer by remember { mutableStateOf(!app.settings.disclaimerAccepted) }

                // 返回键：登录页 → 关登录；非首页 → 回首页；否则交给系统
                BackHandler(enabled = showQwenLogin || pagerState.currentPage != 0) {
                    if (showQwenLogin) {
                        showQwenLogin = false
                    } else {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                }

                // 退出应用时收尾：停隧道、停本地服务（避免端口与子进程残留）
                DisposableEffect(Unit) {
                    onDispose {
                        runCatching { app.tunnel.stop() }
                        runCatching { ApiService.stop(context) }
                    }
                }

                // 启动应用时：恢复悬浮小窗（上次开启且已授权）；免责声明在下方按需弹出
                LaunchedEffect(Unit) {
                    if (app.settings.floatingWindow &&
                        android.provider.Settings.canDrawOverlays(context)
                    ) {
                        com.dslauncher.service.FloatingService.start(context)
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> LauncherScreen(
                                onOpenDeepSeekAccounts = {
                                    qwenAccountsPage = false
                                    scope.launch { pagerState.animateScrollToPage(2) }
                                },
                                onOpenQwenAccounts = {
                                    qwenAccountsPage = true
                                    scope.launch { pagerState.animateScrollToPage(2) }
                                }
                            )
                            1 -> FeaturesScreen()
                            else -> if (qwenAccountsPage) {
                                QwenAccountScreen(onAddAccount = { showQwenLogin = true })
                            } else {
                                AccountScreen(onAddAccount = {
                                    // DeepSeek 登录：独立原生 Activity（对齐 base-2 的 LoginActivity 宿主结构）
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(context, DeepSeekLoginActivity::class.java)
                                        )
                                    }
                                })
                            }
                        }
                    }

                    BottomPageSwitcher(
                        pagerState = pagerState,
                        tabs = TABS,
                        onSelect = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 18.dp)
                    )

                    if (showQwenLogin) {
                        Box(Modifier.fillMaxSize().background(BgBottom)) {
                            QwenLoginScreen(
                                onBack = { showQwenLogin = false },
                                onToken = { showQwenLogin = false }
                            )
                        }
                    }

                    // 免责声明：画在所有内容之上，首次启动必须同意后才能使用
                    if (showDisclaimer) {
                        DisclaimerDialog(
                            onAccept = {
                                app.settings.disclaimerAccepted = true
                                showDisclaimer = false
                            },
                            onExit = { (context as? Activity)?.finishAffinity() }
                        )
                    }
                }
            }
        }
    }
}

/** 首次启动的免责声明：非官方工具、封号风险自担、仅供学习交流与个人使用。 */
@Composable
private fun DisclaimerDialog(onAccept: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 必须二选一，不允许点外部关闭 */ },
        title = { Text("免责声明", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val item = MaterialTheme.typography.bodyMedium
                Text(
                    "1. 本应用是非官方的第三方工具，与 DeepSeek 官方无任何关联；对话能力来自" +
                        "官网网页版接口的逆向实现，接口随时可能变更或失效。",
                    color = TextSecondary, style = item
                )
                Text(
                    "2. 使用官网接口存在账号风控/封禁风险，由此产生的一切后果（包括但不限于" +
                        "限流、禁言、封号）由用户自行承担。",
                    color = TextSecondary, style = item
                )
                Text(
                    "3. 请使用自己的账号，勿将账号、Token、密钥分享给他人；由此导致的账号" +
                        "安全问题由用户自行负责。",
                    color = TextSecondary, style = item
                )
                Text(
                    "4. 本应用仅供学习交流与个人使用，禁止用于任何商业用途或违法违规用途。",
                    color = TextSecondary, style = item
                )
                Text(
                    "5. 继续使用即表示您已阅读并同意以上全部内容。",
                    color = TextPrimary, style = item, fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("同意并继续") }
        },
        dismissButton = {
            TextButton(onClick = onExit) { Text("退出应用") }
        }
    )
}

/**
 * 底部悬浮分页滑块（×1.4 尺寸）。
 *
 * - 圆角胶囊 + 阴影 + 描边 = 「圆润悬空」观感；
 * - 三个分段常显图标 + 文字，宽度稳定，滑块在其上滑动；
 * - 滑块用弹簧动画追踪 Pager 的连续位置（currentPage + 偏移分数）：
 *   跟手滑动时平滑跟随，点击切换时带一点回弹，动效「灵动」；
 * - 单击任意分段直接切换（animateScrollToPage）；
 * - 长按后左右拖动：每越过半段宽度就切一页，松手重置累积量。
 */
@Composable
private fun BottomPageSwitcher(
    pagerState: PagerState,
    tabs: List<Tab>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(45.dp)
    val current by rememberUpdatedState(pagerState.currentPage)
    val density = LocalDensity.current

    // 各分段的几何信息（相对本容器），供滑块计算目标位置
    val segX = remember { mutableStateListOf(0f, 0f, 0f) }
    val segW = remember { mutableStateListOf(0f, 0f, 0f) }

    // Pager 的连续位置（页索引 + 滑动偏移分数）→ 滑块的几何目标
    val rawPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val clamped = rawPos.coerceIn(0f, (tabs.size - 1).coerceAtLeast(0).toFloat())
    val i0 = clamped.toInt().coerceIn(0, tabs.size - 1)
    val i1 = (i0 + 1).coerceIn(0, tabs.size - 1)
    val frac = clamped - i0
    val targetX = segX[i0] + (segX[i1] - segX[i0]) * frac
    val targetW = segW[i0] + (segW[i1] - segW[i0]) * frac

    // 弹簧：中低刚度 + 中等回弹 —— 跟手时接近实时，松手/点击时带「灵动」过冲
    val pillSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val pillX by animateFloatAsState(targetX, pillSpec, label = "pillX")
    val pillW by animateFloatAsState(targetW, pillSpec, label = "pillW")
    val pillVisible = segW.any { it > 0f }

    Box(
        modifier = modifier
            .shadow(25.dp, shape, clip = false)
            .clip(shape)
            .background(CardBg)
            .border(1.dp, CardBorder, shape)
            .padding(8.dp)
            .pointerInput(tabs.size) {
                var acc = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { acc = 0f },
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount.x
                        val seg = size.width.toFloat() / tabs.size
                        val threshold = seg * 0.5f
                        if (acc > threshold) {
                            if (current < tabs.size - 1) onSelect(current + 1)
                            acc = 0f
                        } else if (acc < -threshold) {
                            if (current > 0) onSelect(current - 1)
                            acc = 0f
                        }
                    }
                )
            }
    ) {
        // 滑块：垫在分段下层，弹簧追踪 Pager 连续位置
        if (pillVisible) {
            Box(
                Modifier
                    .offset { IntOffset(pillX.roundToInt(), 0) }
                    .width(with(density) { pillW.toDp() })
                    .height(56.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(PrimaryBlue)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == pagerState.currentPage
                val fg by animateColorAsState(
                    if (selected) Color.White else TextSecondary,
                    tween(220), label = "tabFg$index"
                )
                Row(
                    modifier = Modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 21.dp)
                        .onGloballyPositioned { coords ->
                            segX[index] = coords.positionInParent().x
                            segW[index] = coords.size.width.toFloat()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = fg,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        tab.label,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 22.sp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
