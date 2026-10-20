package com.dslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dslauncher.App
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面内容底部预留高度。
 *
 * 底部那枚悬浮滑块是**浮在内容之上**的，不留白就会把最后一屏内容压住。
 * 滑块放大到 1.4 倍后（约 72dp 高 + 阴影 + 悬浮偏移），104dp 已不够，
 * 提到 140dp。
 */
val BottomNavReserved = 140.dp

/** 是否已授予「显示在其他应用上层」（悬浮窗）权限。 */
private fun canDrawOverlays(context: android.content.Context): Boolean =
    runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false)

/**
 * 第二个页面：功能开关集合。
 *
 * 包含：深度思考、防撤回、局域网开放、开发者模式、内网穿透。
 * 内网穿透基于 Pinggy 的 SSH 反向隧道（纯 Java 实现），
 * 把本地 API 暴露成一个公网 https 地址，任何能上网的客户端都能访问。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen() {
    val context = LocalContext.current
    val app = App.from(context)
    val store = app.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var antiRecall by remember { mutableStateOf(store.antiRecall) }
    var deepThink by remember { mutableStateOf(store.deepThink) }
    var allowLan by remember { mutableStateOf(store.allowLan) }
    var devMode by remember { mutableStateOf(store.devMode) }
    var autoFingerprint by remember { mutableStateOf(app.accounts.autoRotateFingerprint) }

    var tunnelOn by remember { mutableStateOf(app.tunnel.running) }
    var tunnelUrl by remember {
        mutableStateOf(app.tunnel.publicUrl ?: store.tunnelUrl.takeIf { it.isNotBlank() })
    }
    var tunnelBusy by remember { mutableStateOf(false) }
    var tunnelMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(antiRecall) { store.antiRecall = antiRecall }
    LaunchedEffect(deepThink) { store.deepThink = deepThink }
    LaunchedEffect(allowLan) { store.allowLan = allowLan }
    LaunchedEffect(devMode) { store.devMode = devMode }

    // 隧道地址是异步就绪的，轮询刷新
    LaunchedEffect(Unit) {
        while (true) {
            tunnelOn = app.tunnel.running
            app.tunnel.publicUrl?.let { tunnelUrl = it }
            kotlinx.coroutines.delay(1500L)
        }
    }

    val lanIp = remember(allowLan) { if (allowLan) app.lanIp() else null }
    val lanUrl = "http://${lanIp ?: "0.0.0.0"}:${store.port}/v1"

    fun stopTunnel(msg: String = "隧道已停止") {
        scope.launch {
            withContext(Dispatchers.IO) { app.tunnel.stop() }
            tunnelOn = false
            tunnelUrl = null
            tunnelMsg = msg
            store.tunnelEnabled = false
            store.tunnelUrl = ""
        }
    }

    fun toggleTunnel(on: Boolean) {
        if (tunnelBusy) return
        if (!on) {
            stopTunnel()
            return
        }
        tunnelBusy = true
        tunnelOn = true
        scope.launch {
            try {
                if (app.apiServer?.isRunning != true) {
                    tunnelMsg = "提示：本地服务尚未启动，公网地址暂时不可用"
                }
                tunnelMsg = "正在连接 Pinggy 并申请公网地址…"
                val ok = withContext(Dispatchers.IO) { app.tunnel.start(store.port) }
                val url = app.tunnel.publicUrl
                if (ok && url != null) {
                    tunnelUrl = url
                    store.tunnelEnabled = true
                    store.tunnelUrl = url
                    tunnelMsg = "隧道已就绪"
                } else {
                    tunnelMsg = app.tunnel.lastError ?: "隧道启动失败"
                    tunnelOn = false
                    store.tunnelEnabled = false
                }
            } catch (t: Throwable) {
                tunnelMsg = t.message ?: "隧道启动失败"
                tunnelOn = false
            } finally {
                tunnelBusy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(BgTop, BgBottom), radius = 1400f))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            // 抬到悬浮滑块上方，提示不被遮挡
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 96.dp)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp)
                    .padding(bottom = BottomNavReserved),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = PrimaryBlueLight,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "功能",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "对话行为 · 网络开放 · 高级选项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                FeatureToggleCard(
                    icon = Icons.Default.Star,
                    title = "深度思考",
                    descOn = "已开启 · 先推理再作答，首字稍慢，答案更稳（DeepSeek 与 Qwen 均生效）",
                    descOff = "关闭 · 跳过推理链路，首字更快，适合简单问答",
                    checked = deepThink,
                    onChange = { deepThink = it }
                )

                FeatureToggleCard(
                    icon = Icons.Default.Lock,
                    title = "防撤回",
                    descOn = "已开启 · 本地留存消息原文，官网撤回后仍可查看（仅 DeepSeek 通道生效）",
                    descOff = "关闭 · 官网撤回/改写后本地同步消失",
                    checked = antiRecall,
                    onChange = { antiRecall = it }
                )

                FeatureToggleCard(
                    icon = Icons.Default.Share,
                    title = "开放局域网 API",
                    descOn = if (lanIp != null) "已开放 · 同网段设备可直接访问\n$lanUrl"
                    else "已开放 · 未能取到本机局域网 IP",
                    descOff = "仅本机可访问（127.0.0.1）",
                    checked = allowLan,
                    onChange = { allowLan = it },
                    onCopy = if (allowLan) ({
                        clipboard.setText(AnnotatedString(lanUrl))
                        scope.launch { snackbar.showSnackbar("局域网地址已复制") }
                    }) else null
                )

                FeatureToggleCard(
                    icon = Icons.Default.Info,
                    title = "开发者模式",
                    descOn = "已开启 · 按请求注入工具调用协议，支持工具/Agent 链路" +
                        "（DeepSeek 用 [Tool Call:…] 格式，Qwen 用 <tool_call> 格式）",
                    descOff = "关闭 · 纯聊天，不注入任何工具提示词（只想聊天时保持关闭）",
                    checked = devMode,
                    onChange = { devMode = it }
                )

                // ---------------- 悬浮小窗 ----------------
                var floatingOn by remember {
                    mutableStateOf(store.floatingWindow && canDrawOverlays(context))
                }
                FeatureToggleCard(
                    icon = Icons.Default.Settings,
                    title = "悬浮小窗",
                    descOn = "已开启 · 半透明悬浮球常驻屏幕边缘，点击展开快捷控制（调度模式/端口/密钥/启停服务）",
                    descOff = "关闭 · 在屏幕边缘显示可拖动的半透明悬浮球，随时控制服务",
                    checked = floatingOn,
                    onChange = { on ->
                        if (on) {
                            if (canDrawOverlays(context)) {
                                store.floatingWindow = true
                                com.dslauncher.service.FloatingService.start(context)
                                floatingOn = true
                                scope.launch { snackbar.showSnackbar("悬浮小窗已开启：拖动可移位，点击展开") }
                            } else {
                                // 未授权：跳系统悬浮窗授权页；回来后重新拨动开关即可
                                store.floatingWindow = true
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                floatingOn = false
                                scope.launch { snackbar.showSnackbar("请先授予「显示在其他应用上层」权限，再回来打开开关") }
                            }
                        } else {
                            store.floatingWindow = false
                            com.dslauncher.service.FloatingService.stop(context)
                            floatingOn = false
                        }
                    }
                )

                FeatureToggleCard(
                    icon = Icons.Default.Settings,
                    title = "自动更换指纹",
                    descOn = "已开启 · 账号被禁言 / Token 失效或每 ${app.accounts.fingerprintRotateDays} 天" +
                        "自动更换设备指纹，账号间指纹互不重复",
                    descOff = "关闭 · 所有账号沿用登录时的设备指纹（多号同设备易被风控关联）",
                    note = "注：设备指纹只是「这台设备是谁」的身份声明，更换它**不保证降低被禁言概率** —— " +
                        "是否触发风控还取决于 IP、UA、请求频率等；此处仅用于被禁言后换一个设备身份重新登录。",
                    checked = autoFingerprint,
                    onChange = {
                        app.accounts.autoRotateFingerprint = it
                        autoFingerprint = it
                        scope.launch { snackbar.showSnackbar(if (it) "已开启：更换动作发生在禁言/失效/到期时" else "已关闭自动更换指纹") }
                    }
                )

                // ---------------- 内网穿透 ----------------
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = PrimaryBlueLight,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "内网穿透",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                when {
                                    tunnelBusy -> tunnelMsg ?: "正在启动…"
                                    tunnelOn && tunnelUrl != null -> "已就绪 · 任何能上网的客户端都可访问"
                                    else -> "通过 Pinggy 隧道把本地 API 暴露到公网（免注册）"
                                },
                                color = if (tunnelOn && tunnelUrl != null) AccentGreen else TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (tunnelBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = PrimaryBlueLight
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Switch(
                            checked = tunnelOn,
                            onCheckedChange = { toggleTunnel(it) },
                            enabled = !tunnelBusy
                        )
                    }

                    tunnelUrl?.let { u ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .padding(12.dp)
                        ) {
                            Text(
                                "$u/v1",
                                style = MonoStyle,
                                color = AccentGreen,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString("$u/v1"))
                                    scope.launch { snackbar.showSnackbar("公网地址已复制") }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryBlue.copy(alpha = 0.22f),
                                    contentColor = PrimaryBlueLight
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("复制公网地址") }
                            OutlinedButton(
                                onClick = { stopTunnel() },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("停止隧道") }
                        }
                    }

                    tunnelMsg?.takeIf { it.isNotBlank() }?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text(
                        "说明：Pinggy 免费通道由 SSH 反向隧道实现，无需注册、无需安装任何组件。" +
                            "免费隧道约 60 分钟后过期，且地址每次重启都会变化；" +
                            "长期使用建议自建 frp 或使用付费隧道。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBg, shape)
            .border(1.dp, CardBorder, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun FeatureToggleCard(
    icon: ImageVector,
    title: String,
    descOn: String,
    descOff: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onCopy: (() -> Unit)? = null,
    /** 可选的补充说明/免责声明（如「不保证…」），始终显示在描述下方。 */
    note: String? = null
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (checked) AccentGreen else PrimaryBlueLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    if (checked) descOn else descOff,
                    color = if (checked) AccentGreen else TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                note?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        color = Color(0xFFFFA726),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        onCopy?.let { copy ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PrimaryBlue.copy(alpha = 0.12f))
                    .clickable { copy() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = PrimaryBlueLight,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "点击复制地址",
                    color = PrimaryBlueLight,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
