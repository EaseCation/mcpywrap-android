package dev.mcpywrap.engineering

import android.app.Service
import android.content.Intent
import android.os.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 每个有限任务使用新核心进程，更新不会混用模块，也不停止 :game。 */
class CoreWorkerService : Service() {
    class ProgressReporter(private val reply:Messenger?) {
        fun report(label:String) { runCatching { reply?.send(Message.obtain().apply{data=Bundle().apply{putString("progress",label)}}) } }
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val accepted = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!accepted.compareAndSet(false, true)) return@Handler true
        val reply = message.replyTo
        val request = message.data.getString("request") ?: "{}"
        executor.execute {
            val response = try {
                val progress=ProgressReporter(reply)
                progress.report("正在启动工程核心")
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("android_core").callAttr("execute", filesDir.absolutePath, request,progress).toString()
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "核心启动失败").toString()
            }
            try { reply?.send(Message.obtain().apply { data = Bundle().apply { putString("response", response) } }) }
            finally { handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 150) }
        }
        true
    })
    override fun onBind(intent: Intent?) = messenger.binder
    override fun onDestroy() {
        executor.shutdownNow(); super.onDestroy()
        // 客户端取消/超时解绑时也结束当前工作进程，禁止遗留后台构建与下次任务并发。
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
