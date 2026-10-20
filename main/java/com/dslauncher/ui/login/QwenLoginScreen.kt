package com.dslauncher.ui.login

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dslauncher.App
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Qwen 登录页（Compose 部分）。
 *
 * **主通道：独立浏览器登录 Activity（[QwenLoginActivity]）。**
 * chat.qwen.ai 的对话 POST 挂在阿里云 WAF 后面，风控按客户端指纹分级：
 * OkHttp(Java/Conscrypt TLS) 会被 punish / 挂死 / 弹滑块，只有真浏览器栈能过。
 * WebView 就是真 Chromium 栈 —— 在那里登录一次（有滑块就滑一下），
 * 之后 App 的对话请求全部借它的网络栈发出。
 *
 * 之所以用独立 Activity 而不是嵌在 Compose 里：Compose 的 AndroidView 容器
 * 在部分设备上会出现「加载后 WebView 停止绘制」（黑屏）；base-2.apk 用普通
 * Activity + 原生布局完全正常，QwenLoginActivity 就是照它的结构移植的。
 *
 * 备选：邮箱 + 密码直调 /api/v2/auths/signin（纯 API），以及手动粘贴 Token。
 * 后两条能拿到合法 token，但对话仍会被 WAF 拦（除非走浏览器通道）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QwenLoginScreen(onBack: () -> Unit, onToken: (String) -> Unit) {
    val app = App.from(LocalContext.current)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("使用 Qwen（通义）邮箱与密码登录，成功后自动入池") }
    var hintError by remember { mutableStateOf(false) }

    var showManual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    /** 校验并保存 token（密码登录与手动粘贴共用）。 */
    fun acceptToken(token: String) {
        if (busy) return
        busy = true
        hint = "正在校验登录态…"
        hintError = false
        scope.launch {
            val ok = runCatching { app.qwenClient.validateToken(token) }.getOrDefault(false)
            busy = false
            if (ok) {
                // 顺手把页面 Cookie / 过期时间带进池（有 Cookie 才能自动续期）
                val cookies = app.qwenClient.browser?.cookieString().orEmpty()
                val exp = runCatching { app.qwenClient.browser?.readExpiry() }.getOrNull() ?: 0L
                app.qwenRepo.saveToken(token, cookies, exp)
                hint = if (cookies.isNotBlank()) "登录成功（可自动续期）" else "登录成功"
                snackbar.showSnackbar(hint)
                onToken(token)
            } else {
                hint = "登录态校验失败：token 无效或已过期"
                hintError = true
            }
        }
    }

    fun doSignin() {
        if (busy) return
        if (email.isBlank() || password.isBlank()) {
            hint = "请填写邮箱与密码"
            hintError = true
            return
        }
        busy = true
        hint = "正在登录…"
        hintError = false
        scope.launch {
            try {
                val token = app.qwenClient.signin(email, password)
                busy = false
                acceptToken(token)
            } catch (e: Exception) {
                busy = false
                hint = e.message ?: "登录失败"
                hintError = true
            }
        }
    }

    fun openBrowserLogin() {
        runCatching { ctx.startActivity(Intent(ctx, QwenLoginActivity::class.java)) }
            .onFailure {
                hint = "打开浏览器登录失败：${it.message}"
                hintError = true
            }
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("登录 Qwen", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(28.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(CardBg)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("账号密码登录", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        label = { Text("邮箱") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    Button(
                        onClick = { doSignin() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "登录中…" else "登录并添加到账号池") }
                    Text(
                        hint,
                        color = if (hintError) ErrorRed else TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Divider(modifier = Modifier.weight(1f), color = CardBorder)
                    Text("或", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Divider(modifier = Modifier.weight(1f), color = CardBorder)
                }

                Button(
                    onClick = { openBrowserLogin() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("用内置浏览器登录（推荐 · 防风控）") }

                Text(
                    "为什么要用浏览器：chat.qwen.ai 的对话接口有阿里云 WAF 风控，" +
                        "直接发请求会被判成爬虫（空回复/验证码）。内置浏览器用真 Chromium " +
                        "网络栈发请求，才过得去。登录后弹滑块滑一下即可，" +
                        "检测到登录态会自动保存 Token 并关闭页面。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { showManual = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("手动粘贴 Token 登录") }

                Text(
                    "Token 获取：电脑浏览器登录 chat.qwen.ai → F12 → Application → " +
                        "Local Storage → 复制 token 的值。账号池 Token 失效后可随时重新登录添加。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("手动粘贴 Qwen Token") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "电脑浏览器打开 chat.qwen.ai 并登录 → F12 → Application → " +
                            "Local Storage → 复制 token 的值，粘贴到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it.trim() },
                        label = { Text("Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = manualText
                    if (t.isNotBlank()) {
                        showManual = false
                        acceptToken(t)
                    }
                }) { Text("校验并保存") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = PrimaryBlue,
    unfocusedBorderColor = CardBorder
)
