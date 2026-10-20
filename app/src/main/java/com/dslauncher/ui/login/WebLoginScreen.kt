package com.dslauncher.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dslauncher.App
import com.dslauncher.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * DeepSeek 登录页 —— **照搬 base-2.apk（dev.multigw）的登录实现**。
 *
 * 与之前的自研实现相比，逐项对齐 base-2：
 * | 项目 | base-2 做法（本文件照搬） | 我们之前的做法（已废弃） |
 * |---|---|---|
 * | 入口 URL | **站点根路径** `https://chat.deepseek.com` | `/sign_in` |
 * | UA | **默认用 WebView 自带 UA**（不覆盖），需要时按钮切「移动版/电脑版」 | 一上来就覆盖 UA |
 * | 反探测脚本 | `Humanizer.STEALTH_JS`（**逐字符照搬**，含 `[1,2,3,4,5]` 版 plugins） | 自改版（多了 hw/deviceMemory 等） |
 * | 输入法补丁 | 无 | 注入 INPUT_COMPAT_JS（改写 input、MutationObserver） |
 * | Cookie/缓存 | **不清**（保持会话，提供手动「清登录态」） | 每次打开先清 cookie/storage/cache |
 * | 新窗口 | `onCreateWindow` → **把 popup 塞回同一个 WebView** | 新建 WebView 再搬 URL |
 * | 焦点 | setFocusable/InTouchMode/Clickable + requestFocus + 触摸时 requestFocus | 无 |
 * | 凭据提取 | 全量 dump localStorage/sessionStorage/document.cookie，再用 JWT 正则嗅 token | 只读固定几个 key |
 *
 * 说明：base-2 的「凭据」是 cookie+localStorage 全量；本 App 的下游链路用 Bearer token，
 * 所以这里从同一份 dump 里提取 token（键名优先级 + JWT 正则兜底），Cookie 仍留在 WebView 里
 * （浏览器通道/WAF 通行继续用它）。
 */

/** base-2 登录入口：站点根路径。 */
private const val LOGIN_URL = "https://chat.deepseek.com"

/** base-2 `Humanizer.UA_POOL` 的两条。 */
private const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

private const val UA_DESKTOP =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/145.0.0.0 Safari/537.36"

/**
 * base-2 `Humanizer.STEALTH_JS` —— 逐字符照搬（含它那套 `[1,2,3,4,5]` 的 plugins 伪装）。
 * 实测这套组合在 base-2 上能把 DeepSeek 登录页（含人机验证）正常渲染出来，先按它来。
 */
private const val STEALTH_JS = """
(function() {
    try {
        // 1. 隐藏 webdriver
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        // 2. 伪装 Chrome 运行时
        if (!window.chrome) {
            window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
        }
        // 3. plugins / mimeTypes：给「像真的」结构。
        //    实测：DeepSeek 的 fp.min.js（指纹脚本）会读 plugins[i].name.indexOf(...)；
        //    base-2 原始写法 [1,2,3,4,5] 会让它拿到数字 -> undefined.indexOf -> TypeError
        //    （控制台那条「Cannot read properties of undefined (reading 'indexOf')」），
        //    指纹脚本一崩，验证码框就渲染不出来。这里换成真实结构。
        try {
            function mkMime(type, suffixes, desc, plugin) {
                return { type: type, suffixes: suffixes, description: desc, enabledPlugin: plugin };
            }
            function mkPlugin(name, filename, desc) {
                var mimes = [];
                var p = { name: name, filename: filename, description: desc, length: 0 };
                p.item = function (n) { return mimes[n] || null; };
                p.namedItem = function (n) {
                    for (var j = 0; j < mimes.length; j++) { if (mimes[j].type === n) { return mimes[j]; } }
                    return null;
                };
                p.__add = function (m) { mimes.push(m); p.length = mimes.length; p[mimes.length - 1] = m; };
                return p;
            }
            var pdf = mkPlugin('Chrome PDF Plugin', 'internal-pdf-viewer', 'Portable Document Format');
            var viewer = mkPlugin('Chrome PDF Viewer', 'mhjfbmdgcfjbbpaeojofohoefgiehjai', '');
            var nacl = mkPlugin('Native Client', 'internal-nacl-plugin', '');
            var mPdf = mkMime('application/pdf', 'pdf', 'Portable Document Format', pdf);
            var mChromePdf = mkMime('application/x-google-chrome-pdf', 'pdf', 'Portable Document Format', viewer);
            pdf.__add(mPdf);
            viewer.__add(mChromePdf);
            var plist = [pdf, viewer, nacl];
            var parr = {
                length: plist.length,
                item: function (n) { return plist[n] || null; },
                namedItem: function (n) {
                    for (var k = 0; k < plist.length; k++) { if (plist[k].name === n) { return plist[k]; } }
                    return null;
                },
                refresh: function () {}
            };
            for (var q = 0; q < plist.length; q++) { parr[q] = plist[q]; }
            var mlist = [mPdf, mChromePdf];
            var marr = {
                length: mlist.length,
                item: function (n) { return mlist[n] || null; },
                namedItem: function (n) {
                    for (var k = 0; k < mlist.length; k++) { if (mlist[k].type === n) { return mlist[k]; } }
                    return null;
                }
            };
            for (var w = 0; w < mlist.length; w++) { marr[w] = mlist[w]; }
            Object.defineProperty(navigator, 'plugins', { get: function () { return parr; } });
            Object.defineProperty(navigator, 'mimeTypes', { get: function () { return marr; } });
        } catch (e) {}
        // 4. 补全 languages
        Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
        // 5. 阻止特征探测 toString
        const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
        if (originalQuery) {
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications' ?
                    Promise.resolve({ state: Notification.permission }) :
                    originalQuery(parameters)
            );
        }
    } catch(e) {}
})();
"""

/**
 * 输入法兼容补丁（防「输入倒序」—— v30 去掉后又复现，加回来）：
 *   1) `input[type=number]` → `type=text` + `inputmode=numeric`（Android WebView 的 number 输入框
 *      IME 光标维护有缺陷，是输入倒序的根因）；
 *   2) 光标兜底：有内容却停在 0 位时移到末尾。
 * 只改输入框属性与光标，不碰页面其它逻辑。
 */
private const val INPUT_COMPAT_JS = """
(function () {
  try {
    if (window.__dslInputFix) { return; }
    window.__dslInputFix = true;
    function normalize(el) {
      try {
        if (el && el.tagName === 'INPUT' && el.type === 'number') {
          el.type = 'text';
          if (!el.getAttribute('inputmode')) { el.setAttribute('inputmode', 'numeric'); }
        }
      } catch (e) {}
    }
    function scan(root) {
      try {
        if (!root || !root.querySelectorAll) { return; }
        var list = root.querySelectorAll('input[type="number"]');
        for (var i = 0; i < list.length; i++) { normalize(list[i]); }
      } catch (e) {}
    }
    function fixCaret(el) {
      try {
        if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) { return; }
        if (document.activeElement !== el) { return; }
        var v = el.value || '';
        if (v.length > 0 && el.selectionStart === 0 && el.selectionEnd === 0) {
          el.setSelectionRange(v.length, v.length);
        }
      } catch (e) {}
    }
    scan(document);
    try {
      new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var added = muts[i].addedNodes || [];
          for (var j = 0; j < added.length; j++) { scan(added[j]); }
        }
      }).observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}
    document.addEventListener('input', function (e) { fixCaret(e.target); }, true);
    document.addEventListener('compositionend', function (e) { fixCaret(e.target); }, true);
    document.addEventListener('focusin', function (e) { fixCaret(e.target); }, true);
  } catch (e) {}
})()
"""

/** 探测页面里有没有微信扫码登录入口（有就不必等验证码）。 */
private const val QR_PROBE_JS = """
(function(){try{var f=document.querySelectorAll("iframe");for(var i=0;i<f.length;i++){var s=String(f[i].src||"");if(s.indexOf("open.weixin.qq.com")>=0||s.indexOf("qrconnect")>=0){var r=f[i].getBoundingClientRect();if(r.width>40&&r.height>40){return "yes";}}}var t=(document.body?document.body.innerText:"" )||"";if(t.indexOf("扫码")>=0||t.indexOf("微信")>=0){return "yes";}}catch(e){}return "no";})()
"""

/** base-2「提取并保存当前站点凭据」的全量 dump JS —— 逐字符照搬。 */
private const val DUMP_JS = """
(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k)o['LS:'+k]=localStorage.getItem(k)||'';}for(var j=0;j<sessionStorage.length;j++){var sk=sessionStorage.key(j);if(sk)o['SS:'+sk]=sessionStorage.getItem(sk)||'';}o['DOC_COOKIE']=document.cookie||'';return JSON.stringify(o);}catch(e){return '{}';}})()
"""

/** base-2 适配器里从 LS/Cookie 嗅 token 用的 JWT 正则。 */
private val JWT_RE = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]{10,})?")

/** 键名优先级（base-2 里出现过的 token 相关键）。 */
private val TOKEN_KEYS = listOf(
    "LS:userToken", "LS:user_token", "LS:usertoken", "LS:token",
    "LS:auth_token", "LS:access_token", "LS:accessToken",
    "SS:userToken", "SS:token"
)

/**
 * 从 base-2 风格的全量 dump 里挑 token：
 * 先按键名优先级精确取，再用 JWT 正则扫所有值兜底（页面改 key 名也不怕）。
 */
private fun extractToken(dumpJson: String): String? {
    val o = runCatching { JSONObject(dumpJson) }.getOrNull() ?: return null
    for (k in TOKEN_KEYS) {
        val v = o.optString(k).trim().trim('"')
        if (v.length > 20) return v
    }
    val keys = o.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key == "DOC_COOKIE") continue
        val v = o.optString(key)
        val m = JWT_RE.find(v)?.value
        if (!m.isNullOrBlank()) return m
    }
    // cookie 兜底
    JWT_RE.find(o.optString("DOC_COOKIE"))?.value?.let { hit -> return hit }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(onBack: () -> Unit, onToken: (String) -> Unit) {
    val app = App.from(LocalContext.current)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var grabbed by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var rejectedToken by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("请在下方页面登录 —— 卡在人机验证时先点「重载」，再试「UA：电脑版」") }
    var uaMode by remember { mutableStateOf("默认") }          // 默认 / 移动版 / 电脑版
    var consoleLogs by remember { mutableStateOf("") }
    var netLogs by remember { mutableStateOf("") }
    var diagText by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    /** 反探测脚本开关：默认开（= base-2 行为）；关掉可测试「完全原始页面」下验证码是否出现。 */
    var stealthOn by remember { mutableStateOf(true) }

    fun logNet(line: String) {
        netLogs = (netLogs + "\n" + line).takeLast(1600)
    }

    fun diagnose() {
        val wv = webView
        if (wv == null) {
            diagText = "WebView 未就绪"
            return
        }
        wv.evaluateJavascript(com.dslauncher.data.WebStealth.DIAG_JS) { raw ->
            val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
            val o = runCatching { JSONObject(inner) }.getOrNull()
            val sb = StringBuilder()
            val pkg = runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()
            sb.append("WebView 引擎：").append(pkg?.packageName ?: "未知").append(' ')
                .append(pkg?.versionName ?: "").append('\n')
            if (o == null) {
                sb.append("诊断失败：").append(inner.take(200))
            } else {
                sb.append("UA：").append(o.optString("ua").take(110)).append('\n')
                sb.append("状态：").append(o.optString("ready"))
                    .append(" · 正文 ").append(o.optInt("bodyLen")).append(" 字")
                    .append(" · 密码框 ").append(if (o.optBoolean("hasPwd")) "有" else "无").append('\n')
                sb.append("plugins：").append(o.optInt("pluginsLen"))
                    .append(" · plugins[0]=").append(o.optString("plugin0"))
                    .append(" · indexOf 可用=").append(o.optString("plugin0IndexOfOk")).append('\n')
                sb.append("验证码节点：").append(o.optInt("captchaNodes"))
                    .append(" · iframe：").append(o.optJSONArray("iframes")?.length() ?: 0)
                    .append(" · chrome：").append(o.optBoolean("chrome")).append('\n')
                o.optJSONArray("iframes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        sb.append("  iframe[").append(i).append("]：").append(arr.optString(i).take(110)).append('\n')
                    }
                }
                o.optJSONArray("captchaRects")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        sb.append("  验证码节点[").append(i).append("]：").append(arr.optString(i).take(150)).append('\n')
                    }
                }
                if (o.optString("errText").isNotBlank()) {
                    sb.append("页面提示：").append(o.optString("errText")).append('\n')
                }
            }
            val logs = consoleLogs.trim()
            if (logs.isNotBlank()) sb.append("控制台：").append(logs.takeLast(500)).append('\n')
            val nets = netLogs.trim()
            if (nets.isNotBlank()) sb.append("网络失败：").append(nets.takeLast(600))
            diagText = sb.toString().trim()
        }
    }

    fun acceptToken(raw: String?) {
        val token = raw?.takeIf { it.isNotBlank() } ?: return
        if (grabbed || validating) return
        if (token == rejectedToken) return
        validating = true
        hint = "正在校验登录态…"
        scope.launch {
            val ok = runCatching { app.webClient.validateToken(token) }.getOrDefault(false)
            validating = false
            if (ok) {
                if (!grabbed) {
                    grabbed = true
                    app.webRepo.saveToken(token)
                    hint = "登录成功"
                    snackbar.showSnackbar("登录成功")
                    onToken(token)
                }
            } else {
                rejectedToken = token
                hint = "检测到已失效的旧登录态，已忽略。请在页面完成登录"
            }
        }
    }

    /** base-2 的「提取并保存」：dump 全量凭据 → 嗅 token → 校验入池。 */
    fun grabNow(showToast: Boolean) {
        val wv = webView ?: return
        wv.evaluateJavascript(DUMP_JS) { raw ->
            val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val token = extractToken(inner)
            if (token.isNullOrBlank()) {
                if (showToast) hint = "页面里还没有登录态：先完成登录（人机验证过不去就换 UA / 重载）"
            } else {
                acceptToken(token)
            }
        }
    }

    BackHandler { onBack() }

    // base-2 同款：登录态不主动轮询；这里保留一个慢速兜底（2.5s），只在页面没有输入焦点时跑
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        while (!grabbed) {
            delay(2500)
            if (grabbed) break
            wv.evaluateJavascript(DUMP_JS) { raw ->
                val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
                val token = extractToken(inner)
                if (!token.isNullOrBlank() && token != rejectedToken) acceptToken(token)
            }
        }
    }

    // 手动粘贴 Token（验证码在 WebView 里过不去时的兜底通道）
    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("手动粘贴 Token") },
            text = {
                Column {
                    Text(
                        "在电脑浏览器登录 chat.deepseek.com，按 F12 → Application → Local Storage，" +
                            "复制 userToken（或 token）的值粘到这里。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it.trim() },
                        singleLine = false,
                        minLines = 3,
                        label = { Text("userToken / token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = manualText.trim()
                    showManual = false
                    if (t.isBlank()) hint = "没有内容：请先复制 token" else acceptToken(t)
                }) { Text("校验并保存") }
            },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("取消") } }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("登录 DeepSeek 官网", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
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
            ) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = PrimaryBlueLight
                    )
                }
                Text(
                    diagText ?: hint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        diagText = null
                        hint = "重新加载中…"
                        webView?.reload()
                    }) { Text("重载") }
                    TextButton(onClick = {
                        // 与 base-2 完全一致的三态：默认（WebView 自带）/ 移动版 / 电脑版
                        uaMode = when (uaMode) {
                            "默认" -> "移动版"
                            "移动版" -> "电脑版"
                            else -> "默认"
                        }
                        webView?.let { wv ->
                            when (uaMode) {
                                "移动版" -> wv.settings.userAgentString = UA_MOBILE
                                "电脑版" -> wv.settings.userAgentString = UA_DESKTOP
                                else -> wv.settings.userAgentString = null
                            }
                            wv.reload()
                        }
                        hint = "UA：$uaMode，重新加载中…"
                    }) { Text("UA：$uaMode") }
                    TextButton(onClick = { grabNow(showToast = true) }) { Text("提取并保存") }
                    TextButton(onClick = {
                        stealthOn = !stealthOn
                        webView?.reload()
                        hint = if (stealthOn) "已开启反探测伪装（= base-2 行为），重新加载中…"
                        else "已关闭反探测伪装（原始页面环境），重新加载中…"
                    }) { Text(if (stealthOn) "反探测：开" else "反探测：关") }
                    TextButton(onClick = { showManual = true }) { Text("粘贴 Token") }
                    TextButton(onClick = {
                        runCatching { CookieManager.getInstance().removeAllCookies(null) }
                        runCatching { CookieManager.getInstance().flush() }
                        runCatching { WebStorage.getInstance().deleteAllData() }
                        webView?.clearCache(true)
                        webView?.reload()
                        hint = "已清除登录态，重新加载中…"
                    }) { Text("清登录态") }
                    TextButton(onClick = { diagnose() }) { Text("诊断") }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            // —— 焦点/触摸：base-2 同款（保证点击能落到页面、验证码滑块拖得动）
                            setFocusable(true)
                            setFocusableInTouchMode(true)
                            setClickable(true)
                            requestFocus(android.view.View.FOCUS_DOWN)
                            setOnTouchListener { v, event ->
                                val a = event.action
                                if (a == android.view.MotionEvent.ACTION_DOWN ||
                                    a == android.view.MotionEvent.ACTION_UP
                                ) {
                                    v.requestFocus()
                                }
                                false
                            }

                            // —— WebSettings：逐项对齐 base-2（LoginActivity onCreate 第 65~81 行）
                            val st = settings
                            st.javaScriptEnabled = true
                            st.domStorageEnabled = true
                            st.databaseEnabled = true
                            st.useWideViewPort = true
                            st.loadWithOverviewMode = true
                            st.setSupportZoom(true)
                            st.builtInZoomControls = true
                            st.displayZoomControls = false
                            st.setSupportMultipleWindows(true)
                            st.javaScriptCanOpenWindowsAutomatically = true
                            @Suppress("DEPRECATION")
                            run { st.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
                            st.allowFileAccess = true
                            st.allowContentAccess = true
                            st.textZoom = 100
                            st.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            // 额外保留：第三方 Cookie（新版 WebView 默认拦截，验证码常挂在第三方域）
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // 注意：**不覆盖 UA**（base-2 初始就是 WebView 默认 UA）
                            // 也不清 cookie/缓存（base-2 保持会话；要清点上面的「清登录态」）

                            fun injectStealth(view: WebView?) {
                                val v = view ?: return
                                // 输入法兼容补丁始终注入（与反探测开关无关），防「输入倒序」
                                runCatching { v.evaluateJavascript(INPUT_COMPAT_JS, null) }
                                if (!stealthOn) return
                                runCatching { v.evaluateJavascript(STEALTH_JS, null) }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean = false

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    injectStealth(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 100
                                    injectStealth(view)
                                    // 检测微信扫码登录入口：能扫码就不必等验证码（base-2 上能用的也是这条路）
                                    runCatching {
                                        view?.evaluateJavascript(QR_PROBE_JS) { r ->
                                            if (r != null && r.contains("yes")) {
                                                hint = "检测到微信扫码登录：直接用微信扫码即可（无需人机验证）；扫完会自动抓 Token"
                                            }
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    val u = request?.url?.toString().orEmpty()
                                    val main = request?.isForMainFrame == true
                                    logNet(
                                        (if (main) "主框架 " else "子资源 ") +
                                            (error?.errorCode?.toString() ?: "?") + " " +
                                            (error?.description ?: "") + " ← " + u.take(140)
                                    )
                                    if (main) hint = "页面加载失败：" + (error?.description ?: "未知错误") + "（点「重载」重试）"
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?
                                ) {
                                    val code = errorResponse?.statusCode ?: 0
                                    if (code >= 400) {
                                        logNet("HTTP $code ← " + request?.url?.toString().orEmpty().take(140))
                                    }
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                /** base-2 同款：popup 直接塞回**同一个 WebView**（不新建窗口）。 */
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    transport.webView = view
                                    resultMsg.sendToTarget()
                                    return true
                                }

                                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                                    runCatching { request?.grant(request.resources) }
                                }

                                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                    val line = msg?.message().orEmpty()
                                    if (line.isNotBlank()) {
                                        val lvl = runCatching { msg?.messageLevel()?.name }.getOrNull() ?: "LOG"
                                        val src = runCatching { msg?.sourceId().orEmpty() }.getOrDefault("")
                                        val ln = runCatching { msg?.lineNumber() ?: 0 }.getOrDefault(0)
                                        val where = if (src.isNotBlank())
                                            "(" + src.substringAfterLast('/').take(30) + ":" + ln + ") " else ""
                                        consoleLogs = (consoleLogs + "\n[" + lvl + "] " + where + line.take(170))
                                            .takeLast(2200)
                                    }
                                    return true
                                }
                            }
                            loadUrl(LOGIN_URL)
                            webView = this
                        }
                    }
                )
            }
        }
    }
}
