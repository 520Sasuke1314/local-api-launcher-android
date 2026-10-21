# Qwen Token 自动续期（v23）实现说明

## 1. 问题

`chat.qwen.ai` 网页端登录拿到的 `access_token` 是**短寿命 JWT**（实测 `exp` 约 15 分钟）。
旧实现只在登录时抓一次 token 存进账号池，过期后请求 401，被记为「Token 失效」→ **每 15 分钟要重新登录一次**。

## 2. 调研过程与关键证据

### 2.1 SuperAPI 解包产物（`superapi-android-arm64(1).unpacked`，Go 编译的 ELF）

`strings` 里与 Qwen 续期相关的线索：

```
qwen auto sign-in refresh failed     ← 它的续期方式是「用存的账号密码自动重登」
/api/v2/auths/signin                 ← 续期用的登录接口
/api/v2/auths/info
/api/v0/users/login, /api/v0/users/create_guest_challenge
browser: empty token / browser: create profile root
cdp connection closed / Fetch.continueRequest / --headless=new / --no-first-run
X-Qwen-Account, qwen_models_ttl_hours, qwen_account_file
TokenRefresh, RefreshOnAuthError, RefreshTokenInvalidAccount, token_refresh_interval_minutes
```

**结论**：SuperAPI 的 Qwen 通道是 **真·无头 Chromium（CDP）** 驱动的：靠浏览器 profile 里的
Cookie 维持登录态，token 失效时走「**auto sign-in 自动重登**」（要保存账号密码）。
它的续期能力依赖**明文密码**——我们不想这么做（也不该在 App 里存密码）。

### 2.2 线上前端反解（`qwen-chat-fe` 0.3.16 `js/main.js`）

真正好用的东西在前端里 —— `TokenManager` 类（压缩后 `zV`）：

```js
shouldRefresh(){ const e=this.getCurrentState(); if(!e) return true;
  const t = lc() ? 15e4 : 18e4;            // H5 提前 150s / Web 提前 180s
  return e.expiresAt - Date.now() < t; }

performHttpRefresh(){ … uK("/auths/refresh", { baseURL: `${dn}`, method:"GET",
   toast:true, withCredentials:true, nonV1APi:true }) … }
// 成功：{ success:true, data:{ access_token, expires_at } }
```

其中 `dn = ln = apiBaseUrl = "https://auth.qwen.ai/api/v2/"`（**不是** chat.qwen.ai，是 `loginBaseUrl`），
`withCredentials:true` ⇒ **鉴权靠 Cookie**；请求头里额外要求
`x-request-origin = window.location.origin`。

### 2.3 实测探针（curl）

| 请求 | 结果 |
|---|---|
| `GET https://chat.qwen.ai/api/v2/auths/refresh` | `{"success":false,"data":{"code":"not found"}}` ← 走错域 |
| `GET https://auth.qwen.ai/api/v2/auths/refresh`（无附加头） | `Bad_Request / Missing origin` |
| 同上 + `x-request-origin: chat.qwen.ai` | `Bad_Request / Invalid request header` |
| 同上 + 完整头（Version/source/x-request-id/Timezone/x-request-origin） | `Unauthorized / 401 未授权` |
| 同上 + `Cookie: token=abc` | `Unauthorized / 您的会话已过期…` ← **`token` cookie 被识别** |
| 响应头 | `Access-Control-Allow-Origin` + `Allow-Credentials: true`，允许 `x-request-origin/timezone/version/source` → 页面内 fetch 可行 |

**结论**：续期 = `GET https://auth.qwen.ai/api/v2/auths/refresh`，带完整前端式请求头 + 登录 Cookie。

## 3. 实现（不改协议，纯客户端）

| 层 | 文件 | 改动 |
|---|---|---|
| 数据 | `data/qwen/QwenAccountStore.kt` | `QwenAccount` 新增 `cookies` / `expiresAt` / `lastRefreshAt` / `refreshFail`；新增 `updateToken()` `markRefreshFail()` `setCookies()` `dueForRefresh()` `ttlText()` 等；`add()` 支持携带 Cookie 与过期时间 |
| 数据 | `data/SettingsStore.kt` | 新增 `qwenAutoRefresh`（默认开）、`qwenRefreshLeadMin`（默认提前 5 分钟） |
| 网络 | `data/qwen/QwenWebClient.kt` | 新增 `AUTH_BASE/AUTH_REFRESH` 常量、`refreshToken()` / `refreshTokenAuto()`（auth.qwen.ai → auth.qwen.com 兜底）、`Set-Cookie` 轮换合并、JWT `exp` 解析；续期专用 OkHttp client（不挂全局 CookieJar） |
| 浏览器 | `data/qwen/QwenBrowser.kt` | 新增 `cookieString()`（从 CookieManager 抓 chat/auth 域 Cookie）、`refreshInPage()`（页面内 `fetch(..., {credentials:'include'})` 并写回 `qwen_access_token_state`）；`READ_TOKEN_JS` 增加 `expiresAt` |
| 编排 | `data/qwen/QwenTokenRefresher.kt`（新） | 后台每 60s 把「临近过期」的账号续一遍；单账号续期失败不立刻判死（连续 3 次才标记待重登）；取号时 `ensureFresh()` 保新鲜 |
| 编排 | `data/qwen/QwenWebRepository.kt` | 启动即 `refresher.start(scope)`；`saveToken(token, cookies, expiresAt)`；`httpChat` 取号后 `ensureFresh`；401 时**先续期再判失效**；页面 token 变化时同步 Cookie/过期时间 |
| 服务 | `service/ApiService.kt` | 前台服务启动时触发仓库初始化 → 后台续期循环常驻 |
| UI | `ui/login/QwenLoginActivity.kt` | 登录成功存 token + Cookie + 过期时间；新增「续期」按钮；诊断面板显示 Cookie/过期时间 |
| UI | `ui/login/QwenLoginScreen.kt` | 同上（Compose 版入口） |
| UI | `ui/account/QwenAccountScreen.kt` | 新增「Token 自动续期」开关 + 「立即续期全部 Token」/「续期页面会话」按钮；账号卡片显示「剩余 mm:ss / Cookie 已存」；单项「刷新」优先走续期；导入导出带 Cookie/过期时间 |

### 两条续期通道

1. **页面内续期**（优先）：在已登录的 WebView 里 `fetch('https://auth.qwen.ai/api/v2/auths/refresh', {credentials:'include'})` —— 真 Chromium 指纹 + httpOnly Cookie，成功率最高；成功后把新 token 写回 `qwen_access_token_state`，浏览器通道永不掉线。
2. **OkHttp + 账号 Cookie**：池里**任意账号**都能续（每条账号在登录时已存下自己的 Cookie 串）。

## 4. 使用

1. 打开 App → 「Qwen 账号池」→ 每个账号**重新登录一次**（`浏览器登录` → 正常登录即可，会自动存 Cookie）。
   > 升级前就存在的账号没有 Cookie，无法续期；状态行会提示「未存 Cookie（重新登录一次即可自动续期）」。
2. 之后无需任何操作：App 在前台服务存活期间每 60s 检查一次，在到期前 5 分钟自动换新 Token。
3. 也可手动点「立即续期全部 Token」/ 单项的 ⟳（优先续期，续不动才判失效）/ 登录页的「续期」。

## 5. 注意事项

- Cookie 串已加密落盘在 App 私有 SharedPreferences（`qwen_accounts`）；导出账号 JSON 时会带 Cookie（可迁移设备，但请勿外泄该文件）。
- 续期失败会重试（连续 3 次才停用），网络抖动不会误杀账号。
- 若上游把接口从 `auth.qwen.ai` 换掉（前端 `nn` 表），改动点集中在校验 `QwenWebClient.AUTH_REFRESH` 与 `QwenBrowser.REFRESH_JS` 两处常量。
