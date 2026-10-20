package com.dslauncher.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dslauncher.App
import com.dslauncher.data.PickMode
import com.dslauncher.data.formatRemaining
import com.dslauncher.data.qwen.QwenAccount
import com.dslauncher.ui.BottomNavReserved
import com.dslauncher.ui.login.QwenLoginActivity
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Qwen 账号池管理页（与 DeepSeek 的 AccountScreen 同构）：
 * 取号策略、配额/刷新时间、模型名、校验状态、导入导出、账号列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QwenAccountScreen(onAddAccount: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.from(ctx)
    val store = remember { app.qwenAccounts }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var accounts by remember { mutableStateOf(store.list()) }
    var mode by remember { mutableStateOf(store.mode) }
    var quotaText by remember { mutableStateOf(store.defaultQuota.toString()) }
    var coolText by remember { mutableStateOf((store.quotaCooldownMs / 60_000L).toString()) }
    var modelText by remember { mutableStateOf(app.settings.qwenModel) }
    var modelOptions by remember { mutableStateOf(app.qwenRepo.models()) }
    var browserOn by remember { mutableStateOf(app.settings.qwenUseBrowser) }
    var verifying by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(app.settings.qwenAutoRefresh) }
    var refreshing by remember { mutableStateOf(false) }

    var compact by remember { mutableStateOf(store.compactAccounts) }
    var editingId by remember { mutableStateOf<String?>(null) }

    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    var showExport by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }

    fun refresh() {
        store.sweepExpired()
        accounts = store.list()
        mode = store.mode
    }

    LaunchedEffect(Unit) { refresh() }

    // 从浏览器登录页返回时刷新账号列表（新登录的号要立即出现在列表里）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 账号页停留期间每 30s 刷一次列表，让「Token 有效期」倒计时自动走动
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            refresh()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 96.dp)
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("Qwen 账号池", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onAddAccount) {
                        Icon(Icons.Default.Add, contentDescription = "添加账号", tint = PrimaryBlueLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(BgTop, BgBottom), radius = 1400f))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = BottomNavReserved)
            ) {
                item(key = "__settings__") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("取号策略", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PickMode.entries.forEach { m ->
                                    FilterChip(
                                        selected = mode == m,
                                        onClick = {
                                            store.mode = m
                                            mode = m
                                            scope.launch { snackbar.showSnackbar("策略：${m.label()}") }
                                        },
                                        label = { Text(m.label()) }
                                    )
                                }
                            }
                            Text("账号显示", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = compact,
                                    onClick = { compact = true; store.compactAccounts = true },
                                    label = { Text("粗略 · 每行 2 个") }
                                )
                                FilterChip(
                                    selected = !compact,
                                    onClick = { compact = false; store.compactAccounts = false },
                                    label = { Text("详细") }
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = modelText,
                                    onValueChange = { modelText = it.trim() },
                                    label = { Text("Qwen 模型名") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        focusedBorderColor = PrimaryBlue,
                                        unfocusedBorderColor = CardBorder
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        app.settings.qwenModel = modelText.ifBlank { "qwen3.8-max" }
                                        scope.launch { snackbar.showSnackbar("模型已保存：${app.settings.qwenModel}") }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) { Text("保存") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("线上模型", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch { snackbar.showSnackbar("正在获取线上模型列表…") }
                                    app.qwenRepo.refreshModels(force = true) { list ->
                                        scope.launch {
                                            modelOptions = list
                                            snackbar.showSnackbar(
                                                "线上 ${list.size} 个模型：" + list.joinToString("、")
                                            )
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("刷新")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                modelOptions.take(3).forEach { m ->
                                    FilterChip(
                                        selected = m.equals(modelText, ignoreCase = true),
                                        onClick = {
                                            modelText = m
                                            app.settings.qwenModel = m
                                            scope.launch { snackbar.showSnackbar("模型已切换：$m") }
                                        },
                                        label = {
                                            Text(
                                                m.removePrefix("qwen"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }

                            // 内置浏览器通道：chat.qwen.ai 的对话接口有阿里云 WAF 指纹风控，
                            // 只有真 Chromium（WebView）栈过得去；关掉会回退 OkHttp 直连（大概率被拦）。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "内置浏览器通道（防风控）",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        app.qwenClient.browserStatus(),
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Switch(
                                    checked = browserOn,
                                    onCheckedChange = {
                                        browserOn = it
                                        app.settings.qwenUseBrowser = it
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                if (it) "已开启：对话走真浏览器栈" else "已关闭：回退 OkHttp 直连（易被风控）"
                                            )
                                        }
                                    }
                                )
                            }
                            // Token 自动续期：网页端 access_token 只有 ~15 分钟寿命，
                            // 官方前端靠 auth.qwen.ai/api/v2/auths/refresh（凭 Cookie）换新 token。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Token 自动续期（免重复登录）",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        if (autoRefresh) {
                                            "每 60s 检查，到期前 ${app.settings.qwenRefreshLeadMin} 分钟自动换新 Token" +
                                                "（已具备条件 ${store.autoRefreshCapableCount()}/${accounts.size} 个号）"
                                        } else {
                                            "已关闭：Token 过期（约 15 分钟）后需重新登录"
                                        },
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Switch(
                                    checked = autoRefresh,
                                    onCheckedChange = {
                                        autoRefresh = it
                                        app.settings.qwenAutoRefresh = it
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                if (it) "已开启：Token 到期前自动续期" else "已关闭：需手动重新登录"
                                            )
                                        }
                                    }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (refreshing) return@OutlinedButton
                                        refreshing = true
                                        scope.launch {
                                            snackbar.showSnackbar("正在续期全部 Token…")
                                            val out = runCatching {
                                                app.qwenRepo.refresher.refreshDue(force = true)
                                            }.getOrNull()
                                            refresh()
                                            refreshing = false
                                            snackbar.showSnackbar(
                                                out?.summary ?: "续期失败：${app.qwenRepo.refresher.lastErrorText() ?: "未知错误"}"
                                            )
                                        }
                                    },
                                    enabled = !refreshing,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (refreshing) "续期中…" else "立即续期全部 Token") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = runCatching {
                                                app.qwenRepo.refresher.refreshPageSession()
                                            }.getOrDefault(false)
                                            refresh()
                                            snackbar.showSnackbar(
                                                if (ok) (app.qwenRepo.refresher.lastErrorText() ?: "页面会话已续期")
                                                else "页面会话续期失败（页面未加载/未登录？）"
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("续期页面会话") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "登录态存在浏览器里；Cookie 已随账号入库 → Token 到期自动换新，不用再登",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    runCatching {
                                        ctx.startActivity(Intent(ctx, QwenLoginActivity::class.java))
                                    }
                                }) { Text("浏览器登录") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = quotaText,
                                    onValueChange = { quotaText = it.filter { c -> c.isDigit() }.take(4) },
                                    label = { Text("默认配额 (0=不限)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        focusedBorderColor = PrimaryBlue,
                                        unfocusedBorderColor = CardBorder
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        store.defaultQuota = quotaText.toIntOrNull() ?: 0
                                        scope.launch { snackbar.showSnackbar("默认配额已保存") }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) { Text("保存") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = coolText,
                                    onValueChange = { coolText = it.filter { c -> c.isDigit() }.take(5) },
                                    label = { Text("刷新时间 (分钟)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        focusedBorderColor = PrimaryBlue,
                                        unfocusedBorderColor = CardBorder
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        val m = coolText.toIntOrNull() ?: 10
                                        store.quotaCooldownMs = m * 60_000L
                                        scope.launch { snackbar.showSnackbar("刷新时间：$m 分钟") }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) { Text("保存") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val q = quotaText.toIntOrNull() ?: 0
                                        store.applyQuotaToAll(q)
                                        refresh()
                                        scope.launch { snackbar.showSnackbar("配额已应用到全部账号") }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("配额应用全部") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            verifying = true
                                            var ok = 0
                                            var invalid = 0
                                            var renewed = 0
                                            store.list().forEach { acc ->
                                                // 有 Cookie 的先就地续期（续期成功 = Token 新鲜有效）
                                                var valid = acc.canAutoRefresh && runCatching {
                                                    app.qwenRepo.refresher.refresh(acc, force = true)
                                                }.getOrDefault(false)
                                                if (valid) renewed++
                                                else valid = runCatching {
                                                    app.qwenClient.validateToken(acc.token)
                                                }.getOrDefault(false)
                                                if (acc.tokenInvalid || acc.needRelogin || !acc.enabled) {
                                                    // 不清除已有的失效标记，除非这次真的验活了
                                                    if (valid) store.setTokenInvalid(acc.id, false)
                                                } else {
                                                    store.setTokenInvalid(acc.id, !valid)
                                                }
                                                if (valid) ok++ else invalid++
                                            }
                                            refresh()
                                            verifying = false
                                            snackbar.showSnackbar(
                                                "校验完成：正常 $ok（其中自动续期 $renewed）/ 失效 $invalid"
                                            )
                                        }
                                    },
                                    enabled = !verifying,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (verifying) "校验中…" else "校验状态") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        importMsg = null
                                        showImport = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("导入账号") }
                                OutlinedButton(
                                    onClick = {
                                        exportJson = exportTokenJson(store.list())
                                        showExport = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("导出账号") }
                            }
                            OutlinedButton(
                                onClick = {
                                    store.list().forEach { store.resetUsage(it.id) }
                                    refresh()
                                    scope.launch { snackbar.showSnackbar("已刷新全部账号配额") }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("立即刷新全部配额") }
                        }
                    }
                }

                if (accounts.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("还没有 Qwen 账号，点右上角「+」登录添加", color = TextSecondary)
                        }
                    }
                } else if (compact) {
                    items(accounts.chunked(2), key = { row -> row.joinToString("_") { it.id } }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { acc ->
                                Box(Modifier.weight(1f)) {
                                    QwenCompactCard(
                                        acc = acc,
                                        onOpen = { editingId = acc.id },
                                        onToggle = { store.setEnabled(acc.id, !acc.enabled); refresh() }
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(accounts, key = { it.id }) { acc ->
                        QwenAccountCard(
                            acc = acc,
                            onToggle = { store.setEnabled(acc.id, !acc.enabled); refresh() },
                            onDelete = { store.remove(acc.id); refresh() },
                            onReset = { store.resetUsage(acc.id); refresh() },
                            onQuota = { q -> store.setQuota(acc.id, q); refresh() },
                            onRefresh = {
                                scope.launch {
                                    // 先尝试自动续期：这才是「不用每 15 分钟重新登录」的关键
                                    val renewed = runCatching {
                                        app.qwenRepo.refresher.refresh(acc, force = true)
                                    }.getOrDefault(false)
                                    if (renewed) {
                                        refresh()
                                        snackbar.showSnackbar("已自动续期：拿到新 Token（无需重新登录）")
                                        return@launch
                                    }
                                    val valid = runCatching {
                                        app.qwenClient.validateToken(acc.token)
                                    }.getOrDefault(false)
                                    store.setTokenInvalid(acc.id, !valid)
                                    if (valid) {
                                        runCatching {
                                            val info = app.qwenClient.fetchUserInfo(acc.token)
                                            store.setIdentity(acc.id, info.email)
                                        }
                                    }
                                    refresh()
                                    snackbar.showSnackbar(
                                        if (valid) "Token 仍然有效"
                                        else (app.qwenRepo.refresher.lastErrorText()
                                            ?: "Token 已失效：请重新登录一次以启用自动续期")
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // —— 导入对话框：每行一个 token，或 JSON 数组 ——
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入 Qwen 账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "粘贴 token（每行一个）或本 App 导出的 JSON 数组；已存在的账号会原地更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("Token 列表 / JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                    OutlinedButton(onClick = {
                        val t = clipboard.getText()?.text.orEmpty()
                        if (t.isBlank()) importMsg = "剪贴板为空"
                        else { importText = t; importMsg = "已从剪贴板粘贴" }
                    }) { Text("粘贴剪贴板") }
                    importMsg?.let {
                        Text(it, color = PrimaryBlueLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val (added, updated) = importTokens(store, importText)
                    if (added + updated == 0) {
                        importMsg = "没有解析到有效 token"
                    } else {
                        refresh()
                        showImport = false
                        importText = ""
                        scope.launch { snackbar.showSnackbar("导入完成：新增 $added · 更新 $updated") }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }

    // —— 导出对话框 ——
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出 Qwen 账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "以下是 Qwen 账号池 JSON（含 token）。可复制到剪贴板保存；请勿分享给他人。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = exportJson,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("账号 JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExport = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(exportJson))
                    scope.launch { snackbar.showSnackbar("已复制到剪贴板") }
                }) { Text("复制") }
            }
        )
    }

    // —— 账号详情 ——
    val editing = editingId?.let { id -> accounts.firstOrNull { it.id == id } }
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("账号详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(editing.display, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "邮箱：${editing.email ?: "未知（点刷新重取）"}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        qwenStatusLine(editing),
                        color = if (editing.tokenInvalid || editing.needRelogin) ErrorRed else TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = editing.enabled, onCheckedChange = {
                            store.setEnabled(editing.id, it); refresh()
                        })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { store.remove(editing.id); refresh(); editingId = null }) {
                            Text("删除", color = ErrorRed)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingId = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun QwenAccountCard(
    acc: QwenAccount,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onQuota: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    val now = System.currentTimeMillis()
    val cooling = acc.cooldownUntil > now
    val quotaOut = acc.quotaLeft() == 0
    var quotaEdit by remember { mutableStateOf(acc.quota.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            when {
                                acc.tokenInvalid || acc.needRelogin -> ErrorRed
                                !acc.enabled -> TextSecondary
                                cooling || quotaOut -> Color(0xFFFFA726)
                                else -> AccentGreen
                            },
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    acc.display,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "校验登录态", tint = PrimaryBlueLight)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = ErrorRed)
                }
            }
            Text(
                "邮箱：${acc.email ?: "未知（点刷新重取）"}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                qwenStatusLine(acc),
                color = if (acc.tokenInvalid || acc.needRelogin) ErrorRed else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quotaEdit,
                    onValueChange = { quotaEdit = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("配额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = CardBorder
                    )
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onQuota(quotaEdit.toIntOrNull() ?: 0) }) { Text("设为") }
                TextButton(onClick = onReset) { Text("刷新配额") }
                Spacer(Modifier.weight(1f))
                Switch(checked = acc.enabled, onCheckedChange = { onToggle() })
            }
        }
    }
}

@Composable
private fun QwenCompactCard(
    acc: QwenAccount,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val now = System.currentTimeMillis()
    val cooling = acc.cooldownUntil > now
    val quotaOut = acc.quotaLeft() == 0
    val statusText = when {
        acc.tokenInvalid -> "Token失效 · 需重登"
        acc.needRelogin -> "需重登"
        !acc.enabled -> "已停用"
        cooling -> "冷却中"
        quotaOut -> "配额尽"
        acc.expiresAt > 0L -> acc.ttlText(now)
        else -> "正常"
    }
    val statusColor = when {
        acc.tokenInvalid || acc.needRelogin -> ErrorRed
        !acc.enabled -> TextSecondary
        cooling || quotaOut -> Color(0xFFFFA726)
        else -> AccentGreen
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(statusColor, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    acc.label.ifBlank { "Qwen 账号 ${acc.id}" },
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(statusText, color = statusColor, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = acc.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.scale(0.75f)
            )
        }
    }
}

private fun qwenStatusLine(acc: QwenAccount): String {
    val now = System.currentTimeMillis()
    val cooling = acc.cooldownUntil > now
    val quotaOut = acc.quotaLeft() == 0
    return buildString {
        append("已用 ${acc.usedCount}")
        append(if (acc.quota > 0) " / ${acc.quota}" else " / 不限")
        if (cooling) {
            append("  ·  冷却中")
            val left = acc.cooldownUntil - now
            if (left > 0) append("（剩余 ${formatRemaining(left)}）")
        }
        if (quotaOut) append("  ·  配额用尽")
        append("  ·  ").append(acc.ttlText(now))
        if (acc.canAutoRefresh) {
            append(if (acc.refreshFail > 0) "  ·  续期失败 ${acc.refreshFail} 次" else "  ·  Cookie 已存，自动续期生效")
        } else {
            append("  ·  未存 Cookie（重新登录一次即可自动续期）")
        }
        if (acc.tokenInvalid) append("  ·  ✖ Token 已失效")
        else if (acc.needRelogin) append("  ·  需重新登录")
    }
}

/** 导入 token 列表（每行一个，或 JSON 字符串数组）。返回 (新增, 更新)。 */
private fun importTokens(store: com.dslauncher.data.qwen.QwenAccountStore, text: String): Pair<Int, Int> {
    var added = 0
    var updated = 0
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0 to 0
    // 每条：token + （可选）cookies / expiresAt（本 App 导出的 JSON 带这两项）
    data class Entry(val token: String, val cookies: String, val expiresAt: Long)

    val entries = runCatching {
        val arr = org.json.JSONArray(trimmed)
        (0 until arr.length()).mapNotNull { i ->
            when (val o = arr.opt(i)) {
                is String -> o.takeIf { it.isNotBlank() }?.let { Entry(it, "", 0L) }
                is org.json.JSONObject -> o.optString("token").takeIf { it.isNotBlank() }?.let {
                    Entry(it, o.optString("cookies", ""), o.optLong("expiresAt", 0L))
                }

                else -> null
            }
        }
    }.getOrElse { trimmed.lines().map { Entry(it.trim(), "", 0L) } }

    for (e in entries) {
        if (e.token.isBlank() || e.token.length < 8) continue
        val existed = store.list().any { it.token == e.token }
        store.add(e.token, cookies = e.cookies, expiresAt = e.expiresAt)
        if (existed) updated++ else added++
    }
    return added to updated
}

/** 导出为 JSON 数组（含 token 与备注）。 */
private fun exportTokenJson(list: List<QwenAccount>): String {
    val arr = org.json.JSONArray()
    list.forEach { a ->
        arr.put(
            org.json.JSONObject()
                .put("label", a.label)
                .put("email", a.email ?: "")
                .put("token", a.token)
                // 带上 Cookie / 过期时间：导入到别的设备后也能继续自动续期
                .put("cookies", a.cookies)
                .put("expiresAt", a.expiresAt)
        )
    }
    return arr.toString(2)
}

private fun PickMode.label(): String = when (this) {
    PickMode.ROUND_ROBIN -> "轮询"
    PickMode.RANDOM -> "随机"
}
