# DeepSeek API 启动器（Android）

把 **DeepSeek 官网**（chat.deepseek.com）与 **Qwen 官网**（chat.qwen.ai）的对话能力，封装成本机可用的
**OpenAI / Anthropic 双协议 API**，并内置账号池、配额、网页控制台与内置浏览器通道。

> ⚠️ 仅供学习与个人使用。使用网页端接口存在账号风控风险，请自行评估；请勿用于任何违法用途。
> 本项目不提供任何账号，也不内置任何密钥（旧版内置的第三方 Key 已移除）。

---

## 功能一览

### 本地 API（OpenAI / Anthropic 二选一）

| 协议 | base_url | 端点 |
|---|---|---|
| OpenAI 兼容 | `http://<ip>:<port>/v1` | `POST /v1/chat/completions`、`GET /v1/models` |
| Anthropic 兼容 | `http://<ip>:<port>` | `POST /v1/messages`、`POST /v1/messages/count_tokens`、`GET /v1/models` |

- 流式（SSE）、工具调用、思考流（`reasoning_content` / Anthropic `thinking` 块）；
- 密钥两种传法都行：`Authorization: Bearer <key>` 或 `x-api-key: <key>`（未设置则免鉴权）；
- **Anthropic 兼容层**：完整 Messages API 事件序列（`message_start` → `content_block_*` → `message_delta` → `message_stop`），
  工具调用走 `tool_use` 块 + `input_json_delta`。

### 账号与风控

- **多账号池**：DeepSeek / Qwen 双池；轮询 / 随机取号；配额 + 冷却；连续失败自动停用；导入 / 导出；
- **Qwen Token 自动续期**：网页端 access_token 约 15 分钟寿命，按官方前端做法用
  `GET https://auth.qwen.ai/api/v2/auths/refresh`（凭登录 Cookie）自动换新，**不再需要反复登录**；
- **内置浏览器通道（Qwen）**：真 Chromium 栈发请求，过阿里云 WAF 指纹风控；
- **设备指纹**：DeepSeek 登录体带 `device_id`，池内账号互不重复，禁言/失效/到期自动轮换；
- **DSML 解析**：模型直吐 DeepSeek Markup Language（`function_calls`/`invoke`/`parameter`、
  `tool_call_begin` 等变体）时，自动解析成标准 `tool_calls` 并从正文剔除；工具模式下还会在流式阶段就拦住。

### 网页控制台（浏览器打开即用）

启动服务后，浏览器访问 **同一 `IP:端口` 的根路径**（不是 `/v1`）即可得到与 App 同款的
「启动 / 功能 / 账号」界面：开关设置、双池管理、续期 Token、DSML 解析测试等，操作即时生效。

### 可靠性细节（踩过的坑）

- **复读熔断**：正文末尾同一小段连续重复 ≥3 次即停止下发，只保留第一遍；
- **半途异常按正常结束收尾**：已吐过正文时不再发 error 帧，客户端不会显示「响应中断」；
- **不重放已流出的回答**：会话失效时若已吐内容则不再重试（重试会导致「回答重复/突然重来」）；
- **输入法兼容补丁**：修 Android WebView 上 `input[type=number]` 的「输入倒序」。

---

## 构建

环境：JDK 17+、Android SDK（compileSdk 37 / buildTools 37.0.0）。

```bash
# 1) 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 签名文件（仓库不含私钥，需自备；不想签名就删掉 signingConfigs 或改成 debug 签名）
#    app/../dslauncher-release.jks  → 也可改名后在 app/build.gradle.kts 里改路径/口令

# 3) 打包（Release，不混淆但保留裁剪）
./gradlew :app:assembleRelease -Pnoobf

# 产物：app/build/outputs/apk/release/app-release.apk
```

> 说明：`-Pnoobf` = 开启压缩/裁剪但**不混淆**（崩溃栈可读）；`-Pforhardening` = 完全关闭 R8（送加固用基线包）。

---

## 目录结构（主要部分）

```
app/src/main/java/com/dslauncher/
├─ data/
│  ├─ qwen/                 Qwen 通道：客户端 / 浏览器栈 / 账号池 / Token 续期
│  ├─ remote/               DeepSeek 官方接口客户端（含 POW 求解）
│  ├─ repository/           DeepSeek 会话编排（会话链 / 多账号隔离 / 防撤回）
│  ├─ localapi/             本地 API 服务（OpenAI + Anthropic 双协议、网页控制台）
│  ├─ DeviceFingerprints.kt 设备指纹池
│  └─ DsLoginJs.kt          DeepSeek 登录页注入脚本与凭据解析
├─ ui/
│  ├─ LauncherScreen.kt     启动页（端口 / 密钥 / 地址 / 关于）
│  ├─ FeaturesScreen.kt     功能开关
│  ├─ account/              账号池（DeepSeek / Qwen）
│  └─ login/                登录页（DeepSeek / Qwen，独立原生 Activity 挂 WebView）
└─ service/                 前台服务（保活本地 API）与悬浮小窗
```

---

## 常见问题

- **登录页人机验证不出来**：登录页是独立原生 Activity（不在 Compose 里），WebView 配置与已验证可用的实现一致；
  可依次尝试：`换号` → `反探测：开/关` → `UA：移动版/电脑版`，仍不行点「诊断」。
- **加不进账号**：现在有「三重抓取」——① 页面网络请求里的 `Authorization: Bearer`（主）；
  ② localStorage/sessionStorage/cookie 全量 dump（次）；③ 手动「粘贴 Token」。抓到即入库（校验失败也会先保存）。
- **Qwen 提示要重新登录**：升级后每个号**重新登录一次**（存 Cookie），之后自动续期。
- **号池提示「未存 Cookie」**：同上，重新登录一次即可。

## 许可

仅供个人学习研究使用；请遵守 DeepSeek / 阿里云相关服务条款。转载或二次分发请注明来源。
