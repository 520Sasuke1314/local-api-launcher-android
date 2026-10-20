package com.dslauncher.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/**
 * 发布包签名校验。
 *
 * 目的：APK 被二次打包 / 重签后，应用内任何敏感逻辑（本地 API、账户 token 存储）
 * 都不应继续工作。这里在 Application 启动时做一次静默校验：
 *   - 取出当前安装包的签名证书（GET_SIGNING_CERTIFICATES）
 *   - 计算公钥证书的 SHA-256 摘要，与内置白名单比对
 *   - 不一致则静默退出（不弹窗、不打印日志，避免给逆向者定位信息）
 *
 * 白名单值在构建时由 initSignatureVerifier 生成后写入。
 * 由于 R8 会重写类名，ProGuard 规则里对 security 包做了 keep。
 */
object SignatureVerifier {

    // 由 initSignatureVerifier.py 从 keystore 派生并写入的证书 SHA-256 列表。
    // 首次构建前为空占位；运行时若列表为空则跳过校验（避免死锁）。
    private val ALLOWED_SHA256: Set<String> = setOf(
        // 由 dslauncher-release.jks 派生：证书 SHA-256 指纹（大写十六进制，无分隔符）。
        "60DE70DA8F5D276039AB4F19AE9EDF330F829883E099396A2C93BDDC57173DF3"
    )

    /**
     * 校验当前安装包签名是否来自自己的 keystore。
     * @return true = 合法（或未启用校验），false = 被重签
     */
    fun isTrusted(context: Context): Boolean {
        if (ALLOWED_SHA256.isEmpty() || ALLOWED_SHA256.contains("__PLACEHOLDER__")) {
            // 白名单尚未生成：开发期跳过，避免误杀 debug 包。
            return true
        }
        val current = currentSignatureSha256(context) ?: return false
        return ALLOWED_SHA256.any { it.equals(current, ignoreCase = true) }
    }

    /**
     * 立即校验，失败则调用 failAction（默认杀进程）。
     * 不打印任何日志。
     */
    fun enforce(context: Context, failAction: () -> Unit = { killSelf() }) {
        if (!isTrusted(context)) {
            failAction()
        }
    }

    private fun killSelf() {
        // 不做 toast / 日志；直接终止进程。
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    private fun currentSignatureSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signatures.isNullOrEmpty()) return null

            val certFactory = CertificateFactory.getInstance("X.509")
            val sha256 = MessageDigest.getInstance("SHA-256")
            val sb = StringBuilder()
            for (sig in signatures) {
                val cert = certFactory.generateCertificate(sig.toByteArray().inputStream())
                val digest = sha256.digest(cert.encoded)
                for (b in digest) sb.append(String.format("%02X", b))
            }
            sb.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** 供构建脚本调用：把真正的证书摘要写入（保留供未来 OTA 校验）。 */
    @Suppress("unused")
    fun expectedSha256Base64(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            Base64.encodeToString(sig.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }
}
