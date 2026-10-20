package com.dslauncher.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.dslauncher.data.Account
import com.dslauncher.data.PickMode
import com.dslauncher.data.formatRemaining
import com.dslauncher.ui.BottomNavReserved
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 账号池管理页。
 *
 * 功能：
 *  - 列出所有已保存账号（登录自动入池）
 *  - 启用/停用、删除、重置用量
 *  - 设置每个号的请求配额（N 次后自动进入刷新倒计时）
 *  - 修改取号策略：轮询 / 随机
 *  - 导入 / 导出账号（JSON）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onAddAccount: () -> Unit) {
    val app = App.from(LocalContext.current)
    val store = remember { app.accounts }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var accounts by remember { mutableStateOf(store.list()) }
    var mode by remember { mutableStateOf(store.mode) }
    var quotaText by remember { mutableStateOf(store.defaultQuota.toString()) }
    var coolText by remember { mutableStateOf((store.quotaCooldownMs / 60_000L).toString()) }

    // —— 列表显示模式（粗略/详细）与账号详情弹窗 ——
    var compact by remember { mutableStateOf(store.compactAccounts) }
    var editingId by remember { mutableStateOf<String?>(null) }
    /** 「校验状态」进行中（校验要逐个调官网接口，可能耗时）。 */
    var verifying by remember { mutableStateOf(false) }


    // —— 导入账号（JSON）——
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val txt = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (txt.isNullOrBlank()) {
            importMsg = "读取文件失败或文件为空"
        } else {
            importText = txt
            importMsg = "已读取文件，点「导入」执行"
        }
    }

    // —— 导出账号（JSON）——
    var showExport by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val exportSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(exportJson.toByteArray(Charsets.UTF_8))
            }
        }.onSuccess {
            exportMsg = "已保存到文件"
        }.onFailure {
            exportMsg = "保存失败：${it.message}"
        }
    }

    fun refresh() {
        // 先清理到期的刷新倒计时/禁言（配额到期会同时清零已用次数），再读列表；
        // 否则界面会停留在「刷新时间早过去了还显示倒计时 / 配额用尽」。
        store.sweepExpired()
        accounts = store.list()
        mode = store.mode
    }

    // 从「DeepSeek 登录」原生页面返回时刷新账号列表（新登录的号要立刻出现）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进入页面即清理一次过期状态，保证首屏显示正确。
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        containerColor = Color.Transparent,
        // 抬到悬浮滑块上方，提示不被遮挡
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 96.dp)
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("账号池", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
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
            // 整页只有一根 LazyColumn：设置卡与账号列表处在同一个滚动流里，
            // 滚动时一起滑，而不是「上面的设置卡钉死、只有账号区域能滚」。
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
                    modifier = Modifier
                        .fillMaxWidth(),
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
                                onClick = {
                                    compact = true
                                    store.compactAccounts = true
                                },
                                label = { Text("粗略 · 每行 2 个") }
                            )
                            FilterChip(
                                selected = !compact,
                                onClick = {
                                    compact = false
                                    store.compactAccounts = false
                                },
                                label = { Text("详细") }
                            )
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
                        // 自定义刷新时间（分钟）：配额用尽后等待这段时间自动刷新配额
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
                                        val r = app.webRepo.verifyAllStatuses()
                                        refresh()
                                        verifying = false
                                        snackbar.showSnackbar(
                                            "校验完成：正常 ${r.ok} / 禁言 ${r.muted} / Token 失效 ${r.invalid}"
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
                            ) { Text("导入账号（JSON）") }
                            OutlinedButton(
                                onClick = {
                                    exportMsg = null
                                    exportJson = store.exportJson()
                                    showExport = true
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("导出账号（JSON）") }
                        }
                        OutlinedButton(
                            onClick = {
                                store.refreshQuotaAll()
                                refresh()
                                scope.launch { snackbar.showSnackbar("已刷新全部账号配额") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("立即刷新全部配额") }
                    }
                }

                }
                // ---- 账号列表 ----
                if (accounts.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("还没有账号，点右上角「+」登录添加", color = TextSecondary)
                        }
                    }
                } else if (compact) {
                    // 粗略：每行两个小卡，只显示名称 / 状态 / 开关；
                    // 邮箱等详情点开卡片再看。
                    items(
                        items = accounts.chunked(2),
                        key = { row -> row.joinToString("_") { it.id } }
                    ) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { acc ->
                                Box(Modifier.weight(1f)) {
                                    CompactAccountCard(
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
                        AccountCard(
                            acc = acc,
                            onToggle = { store.setEnabled(acc.id, !acc.enabled); refresh() },
                            onDelete = { store.remove(acc.id); refresh() },
                            onReset = { store.resetUsage(acc.id); refresh() },
                            onQuota = { q -> store.setQuota(acc.id, q); refresh() },
                            onRefreshIdentity = {
                                scope.launch {
                                    val r = app.webRepo.refreshIdentity(acc.id, acc.token)
                                    refresh()
                                    snackbar.showSnackbar(r.message)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // —— 导入账号对话框 ——
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "粘贴本 App 导出的 JSON，或从文件选择；支持多账号批量导入，已存在的账号会原地更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val t = clipboard.getText()?.text.orEmpty()
                            if (t.isBlank()) {
                                importMsg = "剪贴板为空"
                            } else {
                                importText = t
                                importMsg = "已从剪贴板粘贴"
                            }
                        }) { Text("粘贴剪贴板") }
                        OutlinedButton(onClick = {
                            importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) { Text("选择文件") }
                    }
                    importMsg?.let {
                        Text(it, color = PrimaryBlueLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = runCatching { store.importExport(importText) }.getOrNull()
                    if (r == null) {
                        importMsg = "导入失败：JSON 解析错误"
                    } else {
                        refresh()
                        showImport = false
                        importText = ""
                        scope.launch {
                            snackbar.showSnackbar("导入完成：新增 ${r.added} · 更新 ${r.updated} · 失败 ${r.invalid}")
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消") }
            }
        )
    }

    // —— 导出账号对话框 ——
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "以下是账号池 JSON。可点「复制」到剪贴板，或点「保存到文件」写入本地；导入到本 App 或兼容工具均可原样还原。",
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
                    exportMsg?.let {
                        Text(it, color = PrimaryBlueLight, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportMsg = null
                    exportSaver.launch("dslauncher_accounts.json")
                }) { Text("保存到文件") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(exportJson))
                        exportMsg = "已复制到剪贴板"
                    }) { Text("复制") }
                    TextButton(onClick = { showExport = false }) { Text("关闭") }
                }
            }
        )
    }

    // —— 账号详情（粗略模式点卡片进入；可查看并修改）——
    val editing = editingId?.let { id -> accounts.firstOrNull { it.id == id } }
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("账号详情") },
            text = {
                AccountDetail(
                    acc = editing,
                    onToggle = { store.setEnabled(editing.id, !editing.enabled); refresh() },
                    onDelete = { store.remove(editing.id); refresh(); editingId = null },
                    onReset = { store.resetUsage(editing.id); refresh() },
                    onQuota = { q -> store.setQuota(editing.id, q); refresh() },
                    onRefreshIdentity = {
                        scope.launch {
                            val r = app.webRepo.refreshIdentity(editing.id, editing.token)
                            refresh()
                            snackbar.showSnackbar(r.message)
                        }
                    },
                    onRotateFingerprint = {
                        val fp = store.rotateFingerprint(editing.id)
                        refresh()
                        scope.launch {
                            if (fp == null) snackbar.showSnackbar("指纹更换失败（账号不存在）")
                            else snackbar.showSnackbar("已更换设备指纹：…${fp.takeLast(8)}（不保证降低禁言概率）")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { editingId = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun AccountCard(
    acc: Account,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onQuota: (Int) -> Unit,
    onRefreshIdentity: () -> Unit
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
                                acc.tokenInvalid -> ErrorRed
                                acc.needRelogin -> ErrorRed
                                acc.mutedNow(now) -> ErrorRed
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
                IconButton(onClick = onRefreshIdentity) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新账号信息", tint = PrimaryBlueLight)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = ErrorRed)
                }
            }
            Text(
                "邮箱：${showEmail(acc)}   手机：${showPhone(acc)}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                accountStatusLine(acc, now),
                color = if (accountHasError(acc, now)) ErrorRed else TextSecondary,
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

/**
 * 粗略模式的小卡：约原卡 1/4 大小，只显示「邮箱 / 状态 / 开关」。
 * 点卡片本体进详情（查看并修改），开关就地启停，互不干扰。
 */
@Composable
private fun CompactAccountCard(
    acc: Account,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val now = System.currentTimeMillis()
    val cooling = acc.cooldownUntil > now
    val quotaOut = acc.quotaLeft() == 0
    val statusText = when {
        acc.tokenInvalid -> "Token失效"
        acc.needRelogin -> "需重登"
        acc.mutedNow(now) -> {
            val left = acc.muteRemainingMs(now)
            if (left != null) "禁言 ${formatRemaining(left)}" else "已禁言"
        }
        !acc.enabled -> "已停用"
        cooling -> "冷却中"
        quotaOut -> "配额尽"
        else -> "正常"
    }
    val statusColor = when {
        acc.tokenInvalid || acc.needRelogin || acc.mutedNow(now) -> ErrorRed
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
                    compactTitle(acc),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    statusText,
                    color = statusColor,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(
                checked = acc.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.scale(0.75f)
            )
        }
    }
}

/** 账号详情内容（弹窗内）：查看全部字段并就地修改。 */
@Composable
private fun AccountDetail(
    acc: Account,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onQuota: (Int) -> Unit,
    onRefreshIdentity: () -> Unit,
    onRotateFingerprint: () -> Unit
) {
    val now = System.currentTimeMillis()
    var quotaEdit by remember(acc.id, acc.quota) { mutableStateOf(acc.quota.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            acc.display,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text("邮箱：${showEmail(acc)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text("手机：${showPhone(acc)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(
            accountStatusLine(acc, now),
            color = if (accountHasError(acc, now)) ErrorRed else TextSecondary,
            style = MaterialTheme.typography.bodySmall
            )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "设备指纹：…${acc.deviceId.takeLast(8).ifBlank { "未设置" }}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onRotateFingerprint) { Text("更换") }
        }
        Text(
            "更换指纹不保证降低禁言概率（风控还看 IP / UA / 请求频率），仅用于换一个设备身份重登。",
            color = Color(0xFFFFA726),
            style = MaterialTheme.typography.labelSmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = quotaEdit,
                onValueChange = { quotaEdit = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("配额") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = CardBorder
                )
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { onQuota(quotaEdit.toIntOrNull() ?: 0) }) { Text("设为") }
            TextButton(onClick = onReset) { Text("刷新配额") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = acc.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefreshIdentity) { Text("刷新信息") }
            TextButton(onClick = onDelete) { Text("删除", color = ErrorRed) }
        }
    }
}

/** 剩余时间文案：如 3分12秒 / 45秒 / 1时02分。 */
private fun remainText(until: Long, now: Long): String {
    val ms = (until - now).coerceAtLeast(0L)
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d时%02d分".format(h, m)
        m > 0 -> "%d分%02d秒".format(m, s)
        else -> "%d秒".format(s)
    }
}

private fun PickMode.label(): String = when (this) {
    PickMode.ROUND_ROBIN -> "轮询"
    PickMode.RANDOM -> "随机"
}

/**
 * 账号状态一行描述。
 *
 * 关键点（对应两个真实问题）：
 *  - 禁言要显示**还有多久**解除，而不是只写「已禁言」；
 *  - token 失效要和禁言区分开单独显示 —— 之前只看禁言，token 过期也显示绿色正常。
 */
private fun accountStatusLine(acc: Account, now: Long): String {
    val cooling = acc.cooldownUntil > now
    val quotaOut = acc.quotaLeft() == 0
    val muteLeft = acc.muteRemainingMs(now)
    return buildString {
        append("已用 ${acc.usedCount}")
        append(if (acc.quota > 0) " / ${acc.quota}" else " / 不限")
        if (cooling) append("  ·  冷却中")
        if (quotaOut) append("  ·  配额用尽")
        if (acc.mutedNow(now)) {
            append("  ·  ⚠ 已禁言")
            append(if (muteLeft != null) "（剩余 ${formatRemaining(muteLeft)}）" else "（未给出解禁时间）")
        }
        if (acc.tokenInvalid) {
            append("  ·  ✖ Token 已失效，请重新登录")
        } else if (acc.needRelogin) {
            append("  ·  需重新登录")
        }
    }
}

/** 状态是否属于「需要用户处理」的错误态。 */
private fun accountHasError(acc: Account, now: Long): Boolean =
    acc.tokenInvalid || acc.mutedNow(now) || acc.needRelogin

/**
 * 详情里展示的邮箱。
 *
 * 官网/导出数据里的邮箱经常是掩码串（`ddd8********nsn@gmail.com`）。
 * 有值就展示：真实值直接显示，掩码值带「（掩码）」标注 —— 掩码至少能认出
 * 绑的是哪个邮箱，比一概「未知」有用。只有完全没拿到才提示刷新。
 */
private fun showEmail(acc: Account): String = showIdentity(acc.email)

private fun showPhone(acc: Account): String = showIdentity(acc.phone)

private fun showIdentity(value: String?): String = when {
    value.isNullOrBlank() -> "未知（点刷新重取）"
    value.contains('*') -> "$value（掩码）"
    else -> value
}

/** 粗略卡片的标题：只认备注名，邮箱一律不在这里出现（点开详情才看邮箱）。 */
private fun compactTitle(acc: Account): String {
    val l = acc.label
    return if (l.isNotBlank() && !l.contains('*')) l else "账号 ${acc.id}"
}
