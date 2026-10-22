package dev.mcpy.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException

/** WorkManager owns installation across navigation and process recreation. */
class RuntimeInstallWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        return try {
            setForeground(notification("正在准备运行环境"))
            var lastNotice=0L
            RuntimeRepository(applicationContext).prepare { p ->
                setProgressAsync(workDataOf("phase" to p.phase,"bytes" to p.bytes,"total" to p.total))
                val now=System.currentTimeMillis()
                if(now-lastNotice>=1000){lastNotice=now;setForegroundAsync(notification(p.phase,p.bytes,p.total))}
            }
            Result.success(workDataOf("phase" to "运行环境已就绪"))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { Result.failure(workDataOf("error" to (e.message?:"安装失败，请重试"))) }
    }
    override suspend fun getForegroundInfo() = notification("正在准备运行环境")
    private fun notification(message: String,bytes:Long=0,total:Long=0): ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("runtime-install","运行环境安装",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"runtime-install")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("mcpy · 安装运行环境")
            .setContentText(message).setOngoing(true)
            .setProgress(100,if(total>0)((bytes*100)/total).toInt().coerceIn(0,100)else 0,total<=0)
            .addAction(android.R.drawable.ic_media_pause,"暂停",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(2101,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val NAME="runtime-install"
        fun start(context: Context) {
            val request=OneTimeWorkRequestBuilder<RuntimeInstallWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            context.getSharedPreferences("installer",0).edit().putString("request",request.id.toString()).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request)
        }
        fun pause(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
