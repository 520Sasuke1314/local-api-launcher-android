package com.dslauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dslauncher.App
import com.dslauncher.MainActivity

/**
 * 前台服务：让本地 API 在后台保持运行，不被系统冻结/回收。
 * 通知栏实时显示运行状态与访问地址。
 */
class ApiService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            App.from(this).stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val app = App.from(this)
        val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 } ?: app.settings.port
        val key = intent?.getStringExtra(EXTRA_KEY) ?: app.settings.apiKey

        ensureChannel(this)

        // 确保服务真的在跑（进程被杀后重启时恢复）
        if (app.apiServer?.isRunning != true) {
            app.startServer(port, key.ifBlank { null })
        }

        // 触发 Qwen 仓库初始化 → 启动 Token 自动续期后台循环
        // （前台服务常驻，是让 token 15 分钟「到期前自动换新」真正跑起来的地方）
        runCatching { app.qwenRepo }

        startForeground(NOTIFICATION_ID, buildNotification(port, app.apiServer?.isRunning == true))
        return START_STICKY
    }

    private fun buildNotification(port: Int, running: Boolean): Notification {
        // 开放局域网时通知里显示局域网地址，方便别的设备直接填
        val app = App.from(this)
        val host = if (app.settings.allowLan) (app.lanIp() ?: "0.0.0.0") else "127.0.0.1"
        val url = "http://$host:$port/v1"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (running) "DeepSeek API 运行中" else "DeepSeek API 启动失败")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText("地址：$url\n端口：$port"))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 服务已在运行时刷新通知（端口/状态变化）。 */
    fun updateNotification(port: Int, running: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(port, running))
    }

    companion object {
        const val CHANNEL_ID = "ds_api_running"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.dslauncher.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEY = "key"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) != null) return
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "API 服务状态",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示本地 API 的运行状态与访问地址"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        fun start(ctx: Context, port: Int, key: String?) {
            val i = Intent(ctx, ApiService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_KEY, key)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, ApiService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
