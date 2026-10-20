package com.dslauncher.data.tunnel

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.cipher.Cipher
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.transport.mac.MAC
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthNone
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.security.Security
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 内网穿透（Pinggy 隧道）。
 *
 * Pinggy 本质上就是一个**免注册的 SSH 反向隧道服务**：
 *
 *     ssh -p 443 -R0:localhost:<port> a.pinggy.io
 *
 * 连上之后它会把分配到的公网地址打在会话输出里，例如：
 *
 *     https://xxxx-1-2-3-4.free.pinggy.net
 *     https://yyyy-1-2-3-4.run.pinggy-free.link
 *
 * 之所以选它而不是 cloudflared：cloudflared 是个 Go 二进制，Android 10+ 禁止执行
 * 应用可写目录里的文件（W^X），必须把 30–40MB 的二进制塞进 APK 的 jniLibs 才能跑。
 * Pinggy 走的是标准 SSH 协议，用纯 Java 的 sshj 就能实现 —— APK 几乎不涨，
 * 也不用申请任何执行权限。
 *
 * 实现要点：
 *  - 远端转发用 `-R0`（端口填 0，由服务端分配），本地 socket 用
 *    [SocketForwardingConnectListener] 接回 127.0.0.1:<port>；
 *  - Pinggy **只接受交互式 shell 会话**（exec 带命令会被它拒绝），所以用
 *    `startSession().startShell()` 并读取输出流来抓公网地址；
 *  - 免费版通道 60 分钟自动过期，且地址每次重启都会变。
 */
class PinggyTunnel {

    @Volatile
    var running: Boolean = false
        private set

    /** 分配到的公网地址，形如 https://xxxx.run.pinggy-free.link（不带 /v1）。 */
    @Volatile
    var publicUrl: String? = null
        private set

    /** 最近一次错误，供 UI 展示。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 当前隧道指向的本地端口。 */
    @Volatile
    var targetPort: Int = 0
        private set

    private var ssh: SSHClient? = null
    private var shell: Session.Shell? = null
    private var readerThread: Thread? = null

    /**
     * 建立隧道。阻塞直到拿到公网地址或超时（约 45 秒）。
     *
     * 该方法必须在后台线程调用（耗时操作）。
     */
    fun start(port: Int): Boolean {
        stop()
        targetPort = port
        publicUrl = null
        lastError = null

        for (host in HOSTS) {
            val ok = runCatching { attempt(host, port) }.getOrElse { t ->
                lastError = t.message ?: t.javaClass.simpleName
                false
            }
            if (ok) return true
            // 换下一个入口再试
            runCatching { ssh?.close() }
            ssh = null
            shell = null
            running = false
        }
        if (lastError == null) lastError = "无法连接 Pinggy（网络受限？）"
        return false
    }

    /**
     * Android 上必须避开「只有完整版 BouncyCastle 才提供」的算法。
     *
     * 原因：Android 系统里本来就有一个名叫 `BC` 的残缺 BouncyCastle provider，
     * sshj 用 `Security.addProvider()` 想把自带的完整版注册成 `BC` 会因**重名失败**，
     * 而它随后仍然按名字 `BC` 去查算法 —— 查到的其实是系统那份，
     * 于是握手第一步就抛：
     *
     *     no such algorithm: X25519 for provider BC
     *
     * 这里把算法表裁到「标准 JCE 就能实现」的范围：
     *   - KEX：去掉所有依赖 25519 曲线的算法 —— 除了 `curve25519-sha256`，
     *     还必须去掉混合后量子的 `sntrup761x25519-sha512@openssh.com`
     *     （它的名字里是 `x25519` 而不是 `curve25519`，旧过滤条件正好漏掉了它，
     *     于是服务端优先协商这个算法时依旧会去 BC 查 `X25519` 并报错）；
     *   - Cipher：只留 AES / 3DES；
     *   - MAC：只留 hmac-sha*。
     *
     * 同时由 [installBouncyCastle] 把系统残缺 `BC` 顶掉，双保险。
     */
    private fun androidSafeConfig(): DefaultConfig {
        val cfg = DefaultConfig()
        runCatching {
            cfg.setKeyExchangeFactories(
                cfg.keyExchangeFactories.filterNot { kexUnsafe(it.name) }
            )
            cfg.setCipherFactories(
                cfg.cipherFactories.filter {
                    val n = it.name.lowercase()
                    n.startsWith("aes") || n.startsWith("3des")
                }
            )
            cfg.setMACFactories(
                cfg.macFactories.filter {
                    val n = it.name.lowercase()
                    n.startsWith("hmac-sha")
                }
            )
        }
        return cfg
    }

    /** 名字里带 25519 曲线 / sntrup 混合 / XDH 的 KEX 一律不用（Android 上 BC 不可靠）。 */
    private fun kexUnsafe(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("25519") ||
            n.contains("curve") ||
            n.contains("sntrup") ||
            n.contains("xdh")
    }

    private fun newClient(): SSHClient = SSHClient(androidSafeConfig())

    private fun attempt(host: String, port: Int): Boolean {
        val client = newClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 20_000
        client.timeout = 0
        client.connect(host, SSH_PORT)

        // Pinggy 免费通道不做身份校验：先试 none，失败退回空密码。
        try {
            client.auth("pinggy", AuthNone())
        } catch (_: Throwable) {
            client.authPassword("pinggy", "")
        }

        // 远端转发：端口 0 = 由服务端分配；进来的连接接到本地 127.0.0.1:port
        val forwarder = client.remotePortForwarder
        forwarder.bind(
            RemotePortForwarder.Forward(0),
            SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", port))
        )

        // 保持长连接，避免 NAT 空闲超时把隧道掐断
        runCatching { client.connection.keepAlive.keepAliveInterval = 30 }

        val session = client.startSession()
        val sh = session.startShell()

        ssh = client
        shell = sh
        running = true

        val latch = CountDownLatch(1)
        val collected = Collections.synchronizedList(ArrayList<String>())

        readerThread = Thread({
            try {
                val br = BufferedReader(InputStreamReader(sh.inputStream))
                var line = br.readLine()
                while (line != null) {
                    for (m in URL_RE.findAll(line)) {
                        collected.add(m.value)
                    }
                    if (collected.isNotEmpty()) latch.countDown()
                    line = br.readLine()
                }
            } catch (_: Throwable) {
                // 连接被关闭 / 中断，正常退出
            } finally {
                latch.countDown()
                running = false
                publicUrl = null
            }
        }, "pinggy-reader").also {
            it.isDaemon = true
            it.start()
        }

        latch.await(45, TimeUnit.SECONDS)
        // 两个地址是先后打印的，等一下把「更稳的那个」收全
        Thread.sleep(2500)

        val all = synchronized(collected) { collected.toList() }
        val best = all.firstOrNull { it.contains("run.pinggy-free.link") }
            ?: all.firstOrNull { it.contains("free.pinggy.net") }
            ?: all.firstOrNull()
        if (best == null) {
            lastError = "未在超时内取得公网地址（Pinggy 免费通道可能繁忙，稍后重试）"
            stop()
            return false
        }
        publicUrl = best
        lastError = null
        return true
    }

    /** 关闭隧道，释放 SSH 连接。可重复调用。 */
    fun stop() {
        readerThread?.interrupt()
        readerThread = null
        runCatching { shell?.close() }
        shell = null
        runCatching { ssh?.close() }
        ssh = null
        running = false
        publicUrl = null
    }

    companion object {
        /** Pinggy 入口（443 端口走 SSH，能穿过大多数只放行 https 的网络）。 */
        private val HOSTS = listOf("a.pinggy.io", "free.pinggy.io")
        private const val SSH_PORT = 443

        private val URL_RE = Regex(
            """https://[A-Za-z0-9][A-Za-z0-9.-]*\.(?:run\.pinggy-free\.link|free\.pinggy\.net|pinggy\.link)"""
        )

        init {
            installBouncyCastle()
        }

        /**
         * 关键修复：Android 系统自带一个精简版 BouncyCastle（provider 名 `BC`），
         * 它没有 `X25519` / `XDH` 等算法。sshj 想用 `Security.addProvider()` 把
         * APK 里随依赖打包的**完整版** BC 注册成 `BC`，却因系统那份已占用同名而失败；
         * 之后 sshj 仍按名字 `BC` 查算法，命中的是系统精简版，于是抛：
         *
         *     no such algorithm: X25519 for provider BC
         *
         * 做法：先把系统那份 `BC` 顶掉，再注册完整版。
         * 个别 OEM 会锁定 provider，失败不致命 —— [androidSafeConfig] 的算法裁剪仍可兜底。
         */
        private fun installBouncyCastle() {
            try {
                val existing = Security.getProvider("BC")
                val full = BouncyCastleProvider::class.java.name
                if (existing != null && existing.javaClass.name != full) {
                    Security.removeProvider("BC")
                }
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
