package com.dslauncher

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import com.dslauncher.data.AccountStore
import com.dslauncher.data.SettingsStore
import com.dslauncher.data.localapi.LocalApiServer
import com.dslauncher.data.qwen.QwenAccountStore
import com.dslauncher.data.qwen.QwenWebClient
import com.dslauncher.data.qwen.QwenWebRepository
import com.dslauncher.data.remote.DeepSeekWebClient
import com.dslauncher.data.repository.DeepSeekWebRepository
import com.dslauncher.data.tunnel.PinggyTunnel
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 发布版签名校验：被二次打包/重签则静默退出，不打印任何日志。
        com.dslauncher.security.SignatureVerifier.enforce(this)
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val webClient: DeepSeekWebClient by lazy { DeepSeekWebClient(okHttp) }
    val webRepo: DeepSeekWebRepository by lazy { DeepSeekWebRepository(this, webClient) }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** Qwen 网页端通道：客户端 + 仓库 + 账号池（镜像 DeepSeek 三件套）。 */
    val qwenClient: QwenWebClient by lazy { QwenWebClient(okHttp, this) }
    val qwenRepo: QwenWebRepository by lazy { QwenWebRepository(this, qwenClient) }
    val qwenAccounts: QwenAccountStore by lazy { QwenAccountStore(this) }

    /** 内网穿透：基于 Pinggy 的 SSH 反向隧道，纯 Java 实现，无需任何二进制。 */
    val tunnel: PinggyTunnel by lazy { PinggyTunnel() }
    /** 多账号仓库：登录自动存号、按策略取号、失败自动停用。 */
    val accounts: AccountStore by lazy { AccountStore(this) }

    @Volatile
    var apiServer: LocalApiServer? = null

    /** 启动本地服务；已运行则先停掉旧的。返回是否启动成功。 */
    @Synchronized
    fun startServer(port: Int, key: String?, allowLan: Boolean = settings.allowLan): Boolean {
        apiServer?.stop()
        val server = LocalApiServer(this, port, key, allowLan)
        server.start()
        if (server.isRunning) {
            apiServer = server
            settings.port = port
            settings.apiKey = key.orEmpty()
            settings.allowLan = allowLan
            settings.serviceStarted = true
            settings.serviceRunning = true
            return true
        }
        settings.serviceRunning = false
        return false
    }

    @Synchronized
    fun stopServer() {
        apiServer?.stop()
        apiServer = null
        settings.serviceRunning = false
    }

    @Synchronized
    fun restartServerIfNeeded() {
        if (settings.serviceRunning && apiServer == null) {
            startServer(settings.port, settings.apiKey.ifBlank { null }, settings.allowLan)
        }
    }

    /** 取本机局域网 IPv4（用于展示局域网 API 地址）。 */
    fun lanIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        fun from(ctx: Context): App = ctx.applicationContext as App
    }
}
