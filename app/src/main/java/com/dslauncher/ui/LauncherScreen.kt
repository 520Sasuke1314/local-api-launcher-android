package com.dslauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Build
import com.dslauncher.App
import com.dslauncher.service.ApiService
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BUSY_PORTS = setOf(80, 443, 3000, 5000, 8081)

/** 官方 QQ 群号（点击复制）。 */
private const val GROUP_QQ = "1101774655"

/** 作者署名。 */
private const val AUTHOR = "Gnuning"

private sealed interface PortStatus {
    data object Unknown : PortStatus
    data object Checking : PortStatus
    data object Available : PortStatus
    data object Occupied : PortStatus
}

/**
 * 第一个页面：启动。
 *
 * 只保留启动服务相关的核心：端口 / 密钥 / API 地址 / 启动与停止、
 * DeepSeek / Qwen 入口卡与并行调度卡，最后是关于软件。
 * 深度思考、防撤回、局域网、开发者模式、内网穿透已全部迁到第二个「功能」页；
 * 账号池管理已迁到第三个「账号」页（底部悬浮滑块切换）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    onOpenDeepSeekAccounts: () -> Unit = {},
    onOpenQwenAccounts: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val app = App.from(context)
    val store = app.settings

    // Android 13+ 需要运行时申请通知权限（前台服务通知）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var portText by remember { mutableStateOf(store.port.toString()) }
    var apiKey by remember { mutableStateOf(store.apiKey) }
    var status by remember { mutableStateOf<PortStatus>(PortStatus.Unknown) }
    var running by remember { mutableStateOf(app.apiServer?.isRunning == true) }
    var tunnelUrl by remember { mutableStateOf(app.tunnel.publicUrl) }
    // allowLan 在「功能」页切换，这里必须跟读最新值：
    // 否则切回本页时地址不刷新、启动服务还会绑错网卡（只绑 127.0.0.1）。
    var allowLan by remember { mutableStateOf(store.allowLan) }

    // 两个池的可用账号数（入口卡展示，随轮询刷新）
    var dsUsable by remember { mutableIntStateOf(0) }
    var qwenUsable by remember { mutableIntStateOf(0) }

    LaunchedEffect(portText) {
        portText.toIntOrNull()?.let { if (it in 1..65535) store.port = it }
    }
    LaunchedEffect(apiKey) { store.apiKey = apiKey }

    // 进程重启后，若上次服务是运行态则自动恢复
    LaunchedEffect(Unit) {
        app.restartServerIfNeeded()
        running = app.apiServer?.isRunning == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 隧道地址异步就绪；局域网开关与账号可用数在其它页/其它时间变化，一并轮询同步
    LaunchedEffect(Unit) {
        while (true) {
            tunnelUrl = app.tunnel.publicUrl
            allowLan = store.allowLan
            val now = System.currentTimeMillis()
            dsUsable = app.accounts.list().count { it.available(now) && !it.needRelogin }
            qwenUsable = app.qwenAccounts.list().count { it.available(now) && !it.needRelogin }
            delay(1500L)
        }
    }

    val clipboard = LocalClipboardManager.current
    val port = portText.toIntOrNull()
    val lanIp = remember(allowLan) { if (allowLan) app.lanIp() else null }
    val host = if (allowLan) (lanIp ?: "0.0.0.0") else "127.0.0.1"
    val url = if (port != null && port in 1..65535)
        "http://$host:$port/v1"
    else "http://$host:----/v1"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(colors = listOf(BgTop, BgBottom), radius = 1400f)
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            // 抬到悬浮滑块上方，提示不被遮挡
            snackbarHost = {
                SnackbarHost(
                    snackbarHostState,
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
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Header()
                PortCard(
                    portText = portText,
                    onPortChange = {
                        portText = it.filter { c -> c.isDigit() }.take(5)
                        status = PortStatus.Unknown
                    },
                    status = status,
                    onCheck = {
                        scope.launch {
                            status = PortStatus.Checking
                            delay(700)
                            val p = portText.toIntOrNull()
                            status = if (p == null || p !in 1..65535 || p < 1024 || p in BUSY_PORTS)
                                PortStatus.Occupied else PortStatus.Available
                        }
                    }
                )
                KeyCard(
                    apiKey = apiKey,
                    onKeyChange = { apiKey = it },
                    onCopy = {
                        clipboard.setText(AnnotatedString(apiKey))
                        scope.launch { snackbarHostState.showSnackbar("密钥已复制到剪贴板") }
                    }
                )
                UrlCard(
                    url = url,
                    tunnelUrl = tunnelUrl,
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        scope.launch { snackbarHostState.showSnackbar("地址已复制") }
                    }
                )
                ServiceButton(
                    running = running,
                    port = portText,
                    onStart = {
                        scope.launch {
                            val p = portText.toIntOrNull()
                            if (p == null || p !in 1..65535) {
                                snackbarHostState.showSnackbar("端口无效")
                                return@launch
                            }
                            val ok = withContext(Dispatchers.IO) {
                                app.startServer(p, apiKey.ifBlank { null }, allowLan)
                            }
                            if (ok) {
                                running = true
                                status = PortStatus.Available
                                // 前台服务：后台保活 + 通知栏显示地址
                                ApiService.start(context, p, apiKey.ifBlank { null })
                                snackbarHostState.showSnackbar("服务已启动 · 端口 $p")
                            } else {
                                status = PortStatus.Occupied
                                snackbarHostState.showSnackbar("启动失败：端口被占用或不可用")
                            }
                        }
                    },
                    onStop = {
                        scope.launch {
                            withContext(Dispatchers.IO) { app.stopServer() }
                            runCatching { ApiService.stop(context) }
                            running = false
                            snackbarHostState.showSnackbar("服务已停止")
                        }
                    }
                )
                // —— DeepSeek / Qwen 入口（关于软件上方） ——
                ProviderEntryCard(
                    icon = Icons.Default.Person,
                    title = "DeepSeek",
                    desc = "账号池 · 可用 $dsUsable 个账号",
                    tint = PrimaryBlueLight,
                    onClick = onOpenDeepSeekAccounts
                )
                ProviderEntryCard(
                    icon = Icons.Default.Info,
                    title = "Qwen",
                    desc = "网页登录转 API · 可用 $qwenUsable 个账号",
                    tint = AccentGreen,
                    onClick = onOpenQwenAccounts
                )
                AboutCard(
                    onCopyGroup = {
                        clipboard.setText(AnnotatedString(GROUP_QQ))
                        scope.launch { snackbarHostState.showSnackbar("群号已复制：$GROUP_QQ") }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = PrimaryBlueLight,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "DeepSeek API 启动器",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "本地 OpenAI 兼容接口 · 一键启动 · 作者 $AUTHOR",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBg, shape)
            .border(1.dp, CardBorder, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String, tint: Color = PrimaryBlueLight) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 服务方入口卡（关于软件上方）：DeepSeek / Qwen 账号池跳转。 */
@Composable
private fun ProviderEntryCard(
    icon: ImageVector,
    title: String,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    GlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(icon = icon, title = title, tint = tint)
            Spacer(Modifier.weight(1f))
            Text(
                "管理 ›",
                color = TextSecondary,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(desc, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = PrimaryBlue,
    unfocusedBorderColor = CardBorder,
    cursorColor = PrimaryBlueLight,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedLabelColor = PrimaryBlueLight,
    unfocusedLabelColor = TextSecondary
)

@Composable
private fun PortCard(
    portText: String,
    onPortChange: (String) -> Unit,
    status: PortStatus,
    onCheck: () -> Unit
) {
    GlassCard {
        SectionTitle(Icons.Default.Settings, "端口设置")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = onPortChange,
                label = { Text("端口 (1024–65535)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(20.dp),
                colors = glassFieldColors(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            val checking = status == PortStatus.Checking
            Button(
                onClick = onCheck,
                enabled = !checking,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(56.dp)
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("检查占用")
                }
            }
        }
        PortStatusRow(status)
    }
}

@Composable
private fun PortStatusRow(status: PortStatus) {
    AnimatedVisibility(
        visible = status != PortStatus.Unknown,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(200))
    ) {
        val (color, label) = when (status) {
            PortStatus.Available -> AccentGreen to "端口可用"
            PortStatus.Occupied -> ErrorRed to "端口被占用或属于特权端口"
            PortStatus.Checking -> TextSecondary to "正在检查…"
            else -> TextSecondary to ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun KeyCard(apiKey: String, onKeyChange: (String) -> Unit, onCopy: () -> Unit) {
    GlassCard {
        SectionTitle(Icons.Default.Lock, "密钥设置", AccentGreen)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onKeyChange,
                label = { Text("API Key（可留空）") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = glassFieldColors(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(PrimaryBlue.copy(alpha = 0.18f))
            ) {
                Icon(Icons.Default.Share, contentDescription = "复制密钥", tint = PrimaryBlueLight)
            }
        }
    }
}

@Composable
private fun UrlCard(url: String, tunnelUrl: String?, onCopy: (String) -> Unit) {
    val app = App.from(LocalContext.current)
    // 控制台地址 = API 根地址去掉 /v1（同一个 IP:端口，浏览器打开就是网页控制台）
    val consoleUrl = url.removeSuffix("/v1").removeSuffix("/")
    GlassCard {
        SectionTitle(Icons.Default.Share, "API 地址")

        // 本机 / 局域网地址
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(14.dp)
        ) {
            Text(
                url,
                style = MonoStyle,
                color = PrimaryBlueLight,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 网页控制台入口：同一 IP:端口的根路径，界面与 App 一致
        Spacer(Modifier.height(10.dp))
        Text(
            "网页控制台（与 App 同款界面）",
            color = AccentGreen,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(14.dp)
        ) {
            Text(
                consoleUrl,
                style = MonoStyle,
                color = AccentGreen,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onCopy(consoleUrl) },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentGreen.copy(alpha = 0.22f),
                contentColor = AccentGreen
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("复制控制台地址")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "服务启动后，在电脑/手机浏览器里打开上面这个地址（同一 IP:端口，无需 /v1），" +
                "就能看到与 App 一模一样的启动 / 功能 / 账号界面；操作即时生效。" +
                (if (app.settings.apiKey.isNotBlank()) "已设置 API Key：控制台里填入同一个 Key 即可。" else ""),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "接口双协议 · 客户端二选一",
            color = PrimaryBlueLight,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "OpenAI 兼容：base_url = " + url + "（/chat/completions、/models）\n" +
                "Anthropic 兼容：base_url = " + consoleUrl + "（自动请求 /v1/messages、/v1/messages/count_tokens）\n" +
                "密钥两种传法都行：Authorization: Bearer <key> 或 x-api-key: <key>。",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Button(
            onClick = { onCopy(url) },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryBlue.copy(alpha = 0.22f),
                contentColor = PrimaryBlueLight
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (tunnelUrl != null) "复制本地网址" else "复制网址")
        }

        // 内网穿透地址（开启后显示）
        if (!tunnelUrl.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "内网穿透公网地址",
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(14.dp)
            ) {
                Text(
                    "$tunnelUrl/v1",
                    style = MonoStyle,
                    color = AccentGreen,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { onCopy("$tunnelUrl/v1") },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen.copy(alpha = 0.22f),
                    contentColor = AccentGreen
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("复制公网地址")
            }
        }
    }
}

/**
 * 启动 / 停止按钮。
 *
 * 之前运行中只显示「运行中 · 端口 x」，没有一个明确的停止入口 ——
 * 现在运行时按钮变红并明确写「停止服务」，点一下就停。
 */
@Composable
private fun ServiceButton(
    running: Boolean,
    port: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "scale")
    val container by animateColorAsState(
        if (running) ErrorRed else PrimaryBlue,
        tween(300), label = "color"
    )
    Button(
        onClick = { if (running) onStop() else onStart() },
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .scale(scale)
    ) {
        Icon(
            if (running) Icons.Default.Clear else Icons.Default.PlayArrow,
            contentDescription = null
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "停止服务（当前端口 $port）" else "启动服务",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }

    if (running) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AccentGreen.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "服务运行中 · 端口 $port",
                color = TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AboutCard(onCopyGroup: () -> Unit) {
    GlassCard {
        SectionTitle(Icons.Default.Info, "关于软件")
        Text(
            "DeepSeek API 启动器 v1.03 · 把官网对话能力封装成本地 OpenAI 兼容接口",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureLine("本地 OpenAI 兼容接口：/v1/chat/completions，支持流式输出、工具调用、思考流")
            FeatureLine("Anthropic 兼容接口：/v1/messages（含流式 SSE 事件、thinking、tool_use 与 count_tokens）")
            FeatureLine("多账号池：DeepSeek / Qwen 双池，轮询/随机取号、配额与冷却、批量导入")
            FeatureLine("混合模型：model 选 mixed 时随机请求 DeepSeek + Qwen 双池账号")
            FeatureLine("会话隔离：按「对话 + 账号」隔离官网会话，新对话不再串流")
            FeatureLine("网页控制台：浏览器打开同一「IP:端口」根路径，即得与 App 同款界面（启动/功能/账号）")
            FeatureLine("防撤回快照 · 深度思考开关 · 悬浮小窗 · 局域网开放 · 内网穿透（Pinggy）")
        }

        // 作者署名
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AccentGreen.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("作者：$AUTHOR", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "DeepSeek API 启动器 · Android",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PrimaryBlue.copy(alpha = 0.12f))
                .clickable { onCopyGroup() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = null,
                tint = PrimaryBlueLight,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("QQ 群：$GROUP_QQ", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("点击复制群号", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text("复制", color = PrimaryBlueLight, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row {
        Text("· ", color = PrimaryBlueLight, style = MaterialTheme.typography.bodySmall)
        Text(text, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}
