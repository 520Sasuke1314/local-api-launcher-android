# v26：DeepSeek 登录人机验证修复 + DSML 解析器

## 一、登录页「人机验证/验证码加载不出来」

### 原因（按可能性排序）

1. **UA 被验证码服务判为不可用环境**（最主要）：WebView 默认 UA 带 `; wv)`，
   新版登录页的人机验证渲染前先做环境探测，命中就**不渲染验证码控件**（区域空白/一直转圈）。
2. **缺反 WebView 探测伪装**：`navigator.webdriver === true`、`window.chrome` 缺失、
   `navigator.plugins` 为空、`navigator.languages` 缺失、`permissions.query('notifications')` 抛错。
3. **多窗口/弹窗被禁用**：验证码与风控跳转常用 `window.open` / `target=_blank`，没开
   `setSupportMultipleWindows` 时点了没反应。
4. 旧 session（Cookie/缓存）让页面直接进风控分支。

### 修复

- **UA 改成真机移动版 Chrome**（Pixel 8 Pro / Chrome 126 Mobile，不含 `wv`）；
  面板新增 **「UA：移动版 / 桌面版」** 一键切换。
- 新增 `data/WebStealth.kt`：反 WebView 探测脚本（webdriver / chrome / plugins / languages /
  permissions + hardwareConcurrency / deviceMemory），在 `onPageStarted`、`onPageCommitVisible`、
  `onPageFinished` 各注入一次。
- WebView 配置补齐：多窗口 + `onCreateWindow` 兜回当前 WebView、`mediaPlaybackRequiresUserGesture=false`、
  混合内容放行、定位、第三方 Cookie、`onPermissionRequest` 自动授权、`LOAD_DEFAULT` 缓存。
- 保留输入法兼容补丁（number → text + inputmode=numeric、光标兜底）。
- 工具栏新增：**重载 / UA 切换 / 清缓存 / 清登录态 / 诊断**。
  「诊断」会输出：UA、页面状态、正文长度、密码框有无、验证码相关节点数、iframe 列表、
  `window.chrome` / `webdriver` 实测值、页面错误提示、控制台日志。

> 若验证码仍不出来：点「诊断」把结果发我，能直接区分「节点没渲染」还是「渲染了但被拒」。

## 二、DSML：协议强化 + 内置解析器

### 协议强化（`buildToolInstruction` / `buildQwenToolInstruction`）

明确写死禁令：禁止一切 DSML 与特殊 token ——
`function_calls` / `invoke` / `parameter` 这类以 `dsml`（或 `磐`、`｜dsml｜`）开头的标签，
以及 `<|tool_calls_begin|>` `<|tool_call_begin|>` `<|tool_sep|>` 等；
并强调「唯一合法格式只有 `[Tool Call: 工具名 {"参数":"值"}]`（Qwen 通道为 `<tool_call>{…}</tool_call>`）。

### 内置 DSML 解析器（`LocalApiServer.parseDsmlCalls`）

支持两种形态，**解析成标准 `tool_calls`**（OpenAI 与 Anthropic 两条协议共用同一提取逻辑）：

1. 标签块（前缀宽松匹配，兼容 `dsml` / `磐` / `｜dsml｜` / 带空格变体）：
   `function_calls` → `invoke name="…"` → `parameter name="…" [string="false"]值`；
   `string="false"` 时按 JSON 解析（数字/布尔/null）；也兼容 invoke 体内直接给 JSON。
2. tokenizer 直出：`tool_call_begin … function<sep>名称 {json} … tool_call_end`。

解析后把这些标记**从正文里整块剔除**（含残留 wrapper 标签），正文只留自然语言。
残留清理规则已收紧为「无前缀」或「含 dsml/磐 前缀」，避免误伤模型输出的正常 HTML
（已验证：`<div class="invoke-box">…</div>` 之类不受影响）。

### 流式也会拦住

两端流式（OpenAI `streamChat` 与 Anthropic `streamAnthropic`）的 **hold-back 正则**都补上了
DSML 标记检测：一旦出现 `invoke name=` / `function_calls` / `tool_call_begin`，
立即停止把后续文本当正文流式下发（转入累积模式），改由末尾统一解析成 tool_calls ——
**不会再出现「DSML 被当正文流出去」**。

### 解析器验证（6 组样本 + 1 组反例）

| 样本 | 结果 |
|---|---|
| `磐function_calls` + `磐invoke/磐parameter`（含 `string="false"` 数字） | ✅ `get_weather{city:Paris, days:3}` |
| 带空格变体 `< dsml function_calls> … </dsml invoke>` | ✅ `search{q:北京 天气}` |
| 全角竖线 `<｜｜DSML｜｜…>` | ✅ `exec_command{command:ls -la}` |
| tokenizer 形态 `tool_call_begin … function<sep>…{json}` | ✅ `get_weather{city:Tokyo}` |
| 正文 + DSML 混排 | ✅ 调用解析正确，正文「好的，我来查一下。请稍等。」保留 |
| invoke 体内直接 JSON | ✅ `foo{a:1,b:x}` |
| 正常 HTML（`<div class="invoke-box">`） | ✅ 不误伤 |

## 三、涉及文件

| 文件 | 说明 |
|---|---|
| `data/WebStealth.kt`（新） | 反 WebView 探测脚本 + 诊断脚本 |
| `ui/login/WebLoginScreen.kt` | UA 替换与切换、WebView 配置补齐、注入时机、诊断面板、工具栏 |
| `data/localapi/LocalApiServer.kt` | DSML 协议禁令、`parseDsmlCalls` 解析器、hold-back 正则扩展、残留标签清理 |

---

# v27 补充：DSML 防护全链路 + 自测工具

## 1. 非工具路径也拦 DSML（此前只在「启用工具」时生效）

模型会无视提示词直吐 DSML，如果客户端**没有**声明 tools，旧逻辑会把 DSML 原样当正文流出去。
现在四条正文出口全部收口（OpenAI / Anthropic × 流式 / 非流式）：

- 流式：改用 **hold-back（64 字符）+ 命中标记即停** —— 检测到 `invoke name=` / `function_calls` /
  `tool_call_begin` 立刻停止「边收边发」，末尾统一 `stripDsmlFromText()` 清理后再发；
- 非流式：累计文本先清理再写响应。

涉及位置：`streamChat` 非工具分支、`collectChat` 非工具分支、`streamAnthropic` 非工具分支、
`collectAnthropic` 非工具分支；公共实现 `stripDsmlFromText()` + `DSML_OPEN_RE`。

## 2. 自测工具（同一个解析器）

- **HTTP**：`POST /debug/dsml`，体 `{"text":"<磐function_calls>…"}` →
  `{"input_length":…,"count":1,"calls":[…],"text":"清洗后的正文"}`。
- **网页控制台**：功能页 →「工具箱 · DSML 解析测试」→ 粘贴模型原文 → 点「解析」，
  直接显示解析出的 `tool_calls` 与清洗后的正文。
  （`/workspace/网页控制台预览.html` 里也能点着玩，用的是示例数据。）

## 3. 快速验证样本

```
<磐function_calls>
<磐invoke name="get_weather">
<磐parameter name="city" string="true">Paris</磐parameter>
<磐parameter name="days" string="false">3</磐parameter>
</磐invoke>
</磐function_calls>
```
期望：`count=1`，`calls[0].function.name=get_weather`，`arguments={"city":"Paris","days":3}`，正文为空。

## 4. 构建

- `:app:compileReleaseKotlin` ✓；`:app:assembleRelease -Pnoobf`（R8 压缩）✓
- 产物：`/workspace/dslauncher-release-v27_20261018.apk`（16 MB，同证书）
- dex 校验含：`stripDsmlFromText`、`dsmlDebugJson`、`DSML_OPEN_RE`、`dsml_test`、`/debug/dsml`、
  控制台「DSML 解析测试」卡片

---

# v28：验证码「只有遮罩、没有框」专项排查

## 症状解读

「只有它的遮蔽模糊背景效果，没有验证码框」= 页面的遮罩层已经出来了（说明页面 JS 跑起来了），
但**验证码控件本身没渲染**。两种典型分支：

| 分支 | 现象 | 判定方法 |
|---|---|---|
| A. SDK/iframe 没加载成功 | 网络层面就失败了（域名被墙/DNS/证书） | 诊断里「网络失败」会列出域名 + 错误码 |
| B. 加载了但没渲染/尺寸为 0/跑到屏幕外 | iframe 存在但 `0x0` 或 `top` 在可视区外，或 `display:none` | 诊断里「验证码节点」会打印 `宽x高@坐标 display=… vis=… z=…` |

## 本轮改动

1. **UA 与引擎版本自洽**：UA 里的 Chrome 版本改为**取自本机 WebView 引擎的真实 versionName**
   （`engineChromeVersion()`）。之前写死 `126.x`，而验证码会核对「UA 声明版本 ↔ JS 实际能力」，
   版本对不上反而更像伪造。
2. **子资源网络日志**：`onReceivedError`（主框架 + **子资源**）与 `onReceivedHttpError`
   全部记入「网络失败」；诊断面板直接能看到「哪个域名、错误码/描述」。
3. **诊断增强**：新增 iframe 与验证码相关节点的**尺寸/坐标/display/visibility/z-index**，
   以及「可视高 / 文档高 / 缩放倍率」——用于区分「没加载」还是「渲染在屏幕外」。
4. **新增「缩放：适配 / 100%」开关**：切换 `useWideViewPort` + `loadWithOverviewMode` 后重载。
   若验证码被整页缩放挤到屏幕外或高度算成 0，切到 100% 常能直接看到框。
5. **新增「粘贴 Token」兜底通道**（验证码彻底过不去时也能用）：
   电脑浏览器登录 chat.deepseek.com → `F12` → Application → Local Storage → 复制
   `userToken`（或 `token`）的值 → App 里「粘贴 Token」→「校验并保存」，同样会服务端校验后入池。

## 建议排查顺序

1. 升级后先「清登录态」→「重载」，看验证码是否出现；
2. 不行就点「缩放：100%」再重载（对应分支 B）；
3. 再不行点「诊断」，把结果发我 —— 看「网络失败」与「验证码节点」两段即可定位（A 还是 B）；
4. 急着用就走「粘贴 Token」（电脑端登录一次即可）。

## 构建

- `:app:compileReleaseKotlin` ✓（3m05s）、`:app:assembleRelease -Pnoobf` ✓
- 产物：`/workspace/dslauncher-release-v28_20261018.apk`（16 MB，同证书）
- dex 校验含：`engineUa`、`engineChromeVersion`、`captchaRects`、`onReceivedHttpError`、
  「粘贴 Token」、「缩放：适配」、「网络失败」

---

# v29：找到「诊断失败: null」的真凶 + 修掉一个可能拖垮验证码的小坑

## 1. 你截图里的 `[ERROR] Uncaught SyntaxError: Invalid regular expression: missing /` —— 那是**我上一版注入的诊断脚本自己坏了**

上一版 `DIAG_JS` 里写了一句正则来扫页面错误提示，但在写入时**换行符被写成了真实换行**，
于是注入的 JS 变成一个**跨行的非法正则字面量** → 整段脚本解析失败 → `evaluateJavascript` 返回 null
→ 界面显示「诊断失败: null」。也就是说：

- 这条 SyntaxError **不是 DeepSeek 页面的问题**（是我的脚本，抱歉）；
- 「诊断」按钮此前一直是坏的，等于没有可用信息。

**修复**：`DIAG_JS` 重写，彻底不用正则（改用 `indexOf` 扫描关键词），并对所有取值加了 try/catch。
现在用 Node 做了两轮校验：**10 段注入脚本语法全部通过** + `DIAG_JS` 在 mock DOM 上实际跑通、返回完整字段。

## 2. 页面上那条 `TypeError: Cannot read properties of undefined (reading 'indexOf')` —— 很可能也是我们造成的

之前的反探测脚本把 `navigator.plugins` 伪造成了 `[1,2,3,4,5]`。很多风控/验证码脚本会读
`plugins[i].name.indexOf(...)` / `plugins[i].filename` —— 拿到数字就抛 TypeError，
**异常一旦发生在验证码挂载流程里，验证码框就不会出现**（正好是你看到的现象）。

**修复**：改成「像真的」结构 —— `Chrome PDF Plugin / Chrome PDF Viewer / Native Client`
三个插件对象（含 `name/filename/description/item/namedItem`），并同步补上 `navigator.mimeTypes`
（`application/pdf` 等）。Node mock 验证：`plugins[0].name.indexOf('Chrome')` 正常返回。

## 3. 顺带的诊断增强

- 控制台日志现在带**来源文件 + 行号**（`(xxx.js:123) 消息`）→ 一眼分清「页面脚本报错」还是「注入脚本报错」；
- 状态行与诊断首行都会显示**本机 WebView 引擎**（`WebView 引擎：com.google.android.webview x.y.z`），
  Kotlin 侧直读、不依赖 JS —— 引擎过老是新版验证码渲染不出来的常见原因，这行是判断依据；
- 诊断字段：iframe / 验证码节点的 `宽x高@坐标 display= vis= z= src=`、`可视高/文档高/缩放倍率`、
  页面错误提示、控制台（带来源）、网络失败（域名+错误码）。

## 4. 下一步怎么判断

装 v29 后点「诊断」，看两处即可：

| 看什么 | 结论 |
|---|---|
| 首行引擎版本很老（如 Chrome 8x/9x 或 WebView 包名是厂商自带） | 引擎太旧 → 去应用商店更新 **Android System WebView / Chrome**；或直接用「粘贴 Token」 |
| 「验证码节点 / iframe」里有节点但 `0x0` 或坐标在屏幕外 | 渲染/布局问题 → 试「缩放：100%」或「UA：桌面版」后重载 |
| 「网络失败」里出现验证码域名 + 错误码 | 网络侧被挡（DNS/证书/被墙）→ 换网络，或「粘贴 Token」 |
| 控制台里仍有报错但来源是页面脚本 | 把带文件名的那几行发我，我按具体脚本再处理 |

---

# v30：照搬 base-2.apk 的 DeepSeek 登录（反编译后逐项对齐）

用 `apktool d base-2.apk` 反编译（`dev.multigw`，8 个 dex），定位到登录实现：
`dev.multigw.ui.LoginActivity` + `dev.multigw.gateway.Humanizer` + `dev.multigw.gateway.LoginStore`，
把它的做法**逐项照搬**进 `ui/login/WebLoginScreen.kt`（整文件重写）：

| 项目 | base-2 的做法（现已照搬） | 我们之前的做法（已删除） |
|---|---|---|
| 登录入口 | **站点根路径** `https://chat.deepseek.com`（`LoginStore.SITE_DOMAINS["deepseek"]`） | `/sign_in` |
| UA | **不覆盖**，用 WebView 默认 UA；按钮三态：默认 → 移动版(Pixel 8 Pro/Chrome 126.0.0.0) → 电脑版(Mac/Chrome 145.0.0.0) | 一上来就覆盖成自拼 UA |
| 反探测 | `Humanizer.STEALTH_JS` **逐字符照搬**（含 `[1,2,3,4,5]` 版 plugins 伪装） | 自改版（多了 hw/deviceMemory/真实 plugins） |
| 输入法补丁 | 无 | 注入 INPUT_COMPAT_JS（改 input + MutationObserver，可能干扰验证码） |
| Cookie/缓存 | **不清**（保持会话；要清有手动按钮） | 每次打开先 `removeAllCookies` + 清 storage/cache |
| 新窗口 | `onCreateWindow` → `transport.webView = view`（**popup 塞回同一个 WebView**） | 新建 WebView 再搬 URL |
| 焦点/触摸 | `setFocusable/InTouchMode/Clickable` + `requestFocus(FOCUS_DOWN)` + 触摸时 requestFocus | 无 |
| WebSettings | JS/DOM/database/useWideViewPort/loadWithOverviewMode/supportZoom/builtInZoomControls/displayZoomControls(false)/supportMultipleWindows/jsCanOpenWindows/mixedContent=ALWAYS/allowFileAccess/allowContentAccess/textZoom=100/cacheMode=LOAD_DEFAULT | 基本一致（外加播放/定位等） |
| 凭据提取 | 全量 dump `localStorage + sessionStorage + document.cookie`（`LS:`/`SS:`/`DOC_COOKIE`），再用 JWT 正则 `eyJ…` 嗅 token | 只读固定 key |

**差异处理**：base-2 保存的是「cookie + localStorage」全量凭据；本 App 下游链路要 Bearer token，
所以从**同一份 dump** 里按 base-2 的键名优先级（`userToken/user_token/usertoken/token/auth_token/access_token`）
+ JWT 正则兜底提取 token，Cookie 仍留在 WebView 中（浏览器通道继续用）。

界面按钮：重载 / **UA（默认→移动→电脑）** / **提取并保存** / 粘贴 Token / 清登录态 / 诊断。

产物：`/workspace/dslauncher-release-v30_20261019.apk`（16 MB，同证书）
dex 校验：`https://chat.deepseek.com`、`DOC_COOKIE`、`LS:`、`eyJ[A-Za-z0-9_-]`、`补全 plugins 伪装`、
`Chrome/145.0.0.0`、`Chrome/126.0.0.0 Mobile`，且旧注入 `INPUT_COMPAT` / `GRAB_TOKEN` 已不存在。

---

# v31：从诊断里定位到「验证码框空白」的真因（fp.min.js 崩了）+ 修回输入倒序

## 诊断读出来的三条关键信息

| 现象 | 数据 | 结论 |
|---|---|---|
| 引擎 | `com.android.webview 137.0.7151.72` | **不是引擎老**，排除「要升级 WebView」 |
| 验证码节点 | 3 个：`ds-verify-code-input-form-item` 332x84@234,730、divider、按键 70x22，display=block vis=visible，**iframe: 0** | 容器**在 DOM 里且可见**，但里面是空的 → 渲染流程断在前面（不是尺寸/坐标问题） |
| 控制台 | `[LOG] (fp.min.js:1) TypeError: Cannot read properties of undefined (reading 'indexOf')` | DeepSeek 的**指纹脚本 fp.min.js 崩了** → 验证码厂商拿不到指纹 → 控件不渲染 |

## 真因：base-2 那套 `plugins` 伪装把自己的页面搞崩了

base-2 的 `STEALTH_JS` 第 3 条是：

```js
Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
```

`fp.min.js` 会读 `navigator.plugins[i].name.indexOf(...)`：
`[1,2,3,4,5][0].name` → `undefined` → `undefined.indexOf(...)` →
**`TypeError: Cannot read properties of undefined (reading 'indexOf')`**（与控制台那条完全对上）。

也就是说：**这条伪装把指纹脚本打崩了，验证码因此渲染不出来**。base-2 带着同样的坑还能用，
多半是它没走到这段代码（或它当时已有有效会话/不同账号路径）。

## v31 改动

1. **`plugins` / `mimeTypes` 换成真实结构**：
   `Chrome PDF Plugin / Chrome PDF Viewer / Native Client`（含 `name/filename/description/item/namedItem`），
   并同步提供 `application/pdf` 等 mimeType、`enabledPlugin` 反向引用。
   → `plugins[0].name.indexOf('Chrome')` 可正常工作，`fp.min.js` 不再抛错。
2. **输入法兼容补丁加回来**（v30 去掉后复现「输入倒序」）：`input[type=number]` → `text`+`inputmode=numeric`，
   光标停在 0 位时移到末尾；始终注入，与「反探测」开关无关。
3. 保留了工作区里后来加入的**「反探测：开/关」按钮**与**微信扫码探测 JS**，未做改动。
4. 诊断新增 `plugins：N · plugins[0]=… · indexOf 可用=yes/no` —— 一眼确认这条伪装是否修好。

产物：`/workspace/dslauncher-release-v31_20261019.apk`（16 MB，同证书）
dex 校验：`Chrome PDF Plugin`、`internal-pdf-viewer`、`mimeTypes`、`__dslInputFix`、
`plugin0IndexOfOk`、`DOC_COOKIE`、`qrconnect`（微信探测）、`反探测：开`（用户新增按钮）。

---

# v32：DeepSeek 登录整体搬成「独立原生 Activity」（base-2 的宿主结构）

## 为什么还要再搬一次

v31 已经证明脚本层面没崩了（诊断里 `plugins[0]=Chrome PDF Plugin · indexOf 可用=yes`，
`fp.min.js` 的 TypeError 消失），但验证码框依旧空白。此时与 base-2 剩下的最大结构差异是**宿主**：

| | base-2 | 我们（v31 之前） |
|---|---|---|
| 宿主 | 普通 Activity + 原生 LinearLayout 直接挂 WebView | Compose 页面里的 `AndroidView` |
| 窗口背景 | 浅色 | Compose 背景 |
| WebView 构建时机 | Activity onCreate 内新建 | Compose 工厂内新建 |

Qwen 当初「加载后黑屏」最后就是靠同一招修好的（见 `QwenLoginActivity` 的注释），
所以这次 DeepSeek 也照做。

## v32 改动

1. **新增 `ui/login/DeepSeekLoginActivity.kt`**（纯原生 Activity）：
   - 窗口背景 `#F4F6FA`；
   - 顶部原生按钮条：返回 / 重载 / **UA：默认→移动版→电脑版** / 提取并保存 / 粘贴 Token / 清登录态 / 诊断；
   - WebView 挂进 `LinearLayout`（weight=1），**不经 Compose**；
   - WebSettings / 焦点 / 触摸 / `onCreateWindow`（popup 塞回同一 WebView）/ STEALTH_JS 注入 —— 全部照搬 base-2；
   - 登录成功后：dump localStorage/sessionStorage/cookie → 嗅 token → 服务端校验 → 写入账号池 → 自动关闭。
2. **新增 `data/DsLoginJs.kt`**：把 base-2 的三段脚本（STEALTH_JS / DUMP_JS / UA 池）+ 输入法补丁
   + token 嗅探（键名优先级 + JWT 正则）集中成共用对象，Activity 与旧 Compose 页共用。
3. **`MainActivity`**：账号页的「+ / 浏览器登录」不再打开 Compose 覆盖层，改为
   `startActivity(DeepSeekLoginActivity)`；删除旧覆盖层代码。
4. **`AccountScreen`**：加 ON_RESUME 刷新 —— 从登录页返回时新账号立刻出现在列表里。
5. 旧 `ui/login/WebLoginScreen.kt` 保留但不再被引用（R8 会剥掉）。

产物：`/workspace/dslauncher-release-v32_20261020.apk`（16 MB，同证书）
Manifest 已注册三个 Activity（Main/DeepSeekLogin/QwenLogin）；dex 校验含
`DeepSeekLoginActivity`、`DsLoginJs`、`Chrome PDF Plugin`、`__dslInputFix`、`DOC_COOKIE`、
`eyJ[A-Za-z0-9_-]`、`https://chat.deepseek.com`。

---

# v33：复读熔断 / 莫名中断 / 加不了号 / 按钮点不上

## 1. 模型复读（同一件事反复输出）

- **新增「复读熔断」**（`LocalApiServer.RepeatGuard`）：正文末尾出现「同一小段（8~120 字）连续重复 ≥3 次」
  即判定复读，**立即停止继续下发**，只保留第一遍；流式（OpenAI / Anthropic 两条协议）与非流式都生效。
  命中时 OpenAI 侧以 `finish_reason: "stop"` 正常收尾（不会报错），服务端在 `QwenWebClient.lastUpstreamMeta`
  留一条 `stream error: …` 便于排查。
- **上下文侧已核查**：`buildHistoryTxt` 只取「本轮 user 之前」的内容（不含本轮消息，不会双份喂）；
  工具结果带「已执行，不要重复调用」标注并按大小截断；会话链（session/parent）按账号隔离。
  真正会造成「答案重复」的是下面的「半途重试」，这次一并修了。

## 2. 莫名中断 / 回答突然重来

- **不再「吐过内容还重试」**：DeepSeek 与 Qwen 两条通道原来在会话失效（biz_code 26/24 等）时会
  **重建会话 + 重放同一轮**；如果客户端已经显示了一部分回答，重放就会表现为「内容重复 / 突然重来」。
  现在两条通道都统计「已下发字数」，**只要已经吐过内容就不再重试**（清理会话链留给下一轮自然重建）。
- **半途异常按正常结束收尾**：流式过程中如果上游发生异常（且已吐过正文），不再发 `error` 帧，
  而是发一个正常的结束帧（OpenAI：`stop` + `[DONE]`；Anthropic：`message_delta(end_turn)` + `message_stop`），
  客户端不会再弹「响应中断/错误」。

## 3. 加不了账号：登录进去是「对话页」

- **原因**：v30 起照搬 base-2 用站点根路径 + 不再清 Cookie，于是旧会话还在 → 打开直接是对话页；
  而自动抓取又可能「一进页面就把旧会话抢登入池并关闭」，用户根本没机会操作。
- **修复**（`DeepSeekLoginActivity`）：
  - 新增 **「换号」** 按钮：清 Cookie/存储/缓存后直接打开 `https://chat.deepseek.com/sign_in`；
  - **已登录时不再自动抢登**：打开页面时先取一次当前 token 作为基线；
    自动轮询只在「出现**新**登录态」时才入池并关闭；
  - 页面加载完成后按情况给提示：「检测到已登录的旧会话：点『提取并保存』直接入库；要换号点『换号』」。

## 4. 有些手机点不上按钮

- 登录页原生按钮触摸目标加大：**≥44dp 高**、字号 12→13.5sp、内边距 10/4 → **16/10dp**，
  并加 `isAllCaps=false`（之前 12sp + 4dp 的小按钮在部分机型上很难按准）。

产物：`/workspace/dslauncher-release-v33_20261020.apk`（16 MB，同证书）
dex 校验：`RepeatGuard`、`truncateRepetition`、`换号`、`已清除登录态并打开登录页`、`检测到已登录的旧会话`、`sign_in`。

---

# v35：工具调用修复（「根本调用不了工具」的两个硬伤）

## 硬伤一：工具支持被「开发者模式」门控

```kotlin
// 旧：客户端带了 tools，但只要 App 里没开「开发者模式」就完全忽略
val hasTools = devMode && tools != null && tools.length() > 0
```

客户端（RikkaHub / Cline / 各种 Agent）**每次请求都带 tools**，期望走工具协议；
而 devMode 默认关闭 → 工具提示词不注入、返回也不解析 → 表现就是「根本调用不了工具」。

现在：**带了 tools 就支持**（`tools != null && tools.length() > 0`），纯聊天请求（不带 tools）行为不变。
OpenAI 与 Anthropic 两个入口都改了。

## 硬伤二：参数会被解析成空对象（「command is required」的元凶）

模型经常把 `arguments` 写成 **JSON 字符串**（外面多一层引号）：

```
[Tool Call: workspace_shell {"command":"ls"}]
→ 解析出 arguments = "{\"command\":\"ls\"}"   ← 字符串，不是对象
→ JSONObject(它) 解析失败 → 回退成 {}         ← 参数丢失
→ 客户端收到空参数 → 报「command is required / 缺少参数」
```

修复 `unwrapJsonString()`：先剥一层引号再解析；`makeToolCall` 与 `addParsedJson` 都走它。

## 顺带增强（解析器兜底）

- `addParsedJson` 新增支持：`{"tool_calls":[…]}` 数组包裹、`function` 包裹、`tool`/`args` 别名、字符串 arguments；
- `fallbackExtractCalls()` 新增三种兜底（仅在标记全没命中时启用，避免误伤正文）：
  1. **代码块 JSON**：` ```json {"name":"x","arguments":{…}} ``` ` / ` ```tool_call {…} ``` `；
  2. **未闭合 invoke**（模型少写闭合标签/被截断）：从开标签后取 parameter 平铺或第一段 JSON；
  3. **裸 JSON**：整段就是 `{"name":…,"arguments":…}` / `{"tool_calls":[…]}`。

## 验证

- 编译通过；APK 内 `unwrapJsonString` / `fallbackExtractCalls` 均已在 dex 中；
- 客户端侧验证建议：带 tools 请求 → 模型输出工具块 → 客户端应能拿到**带参数**的 `tool_calls` 并执行。
