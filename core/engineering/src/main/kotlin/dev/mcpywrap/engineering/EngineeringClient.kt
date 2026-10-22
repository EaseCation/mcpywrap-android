package dev.mcpywrap.engineering

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** UI 和将来的外部工具入口都通过这里执行工程任务，失败不回退 Kotlin 组装器。 */
class EngineeringClient(context: Context) {
    private val app = context.applicationContext
    companion object { private val tasks = Mutex() }

    suspend fun request(operation: String, folder: String? = null, version: String? = null, options: JSONObject? = null,
                        onProgress:(String)->Unit={}): JSONObject = tasks.withLock {
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<JSONObject>()
            val ended = CompletableDeferred<Unit>()
            val request = JSONObject().put("operation", operation)
            folder?.let { request.put("folder", it) }; version?.let { request.put("version", it) }
            options?.let { request.put("options", it) }
            var bound = false
            var remote: IBinder? = null
            val death = IBinder.DeathRecipient {
                ended.complete(Unit)
                if (!result.isCompleted) result.completeExceptionally(IllegalStateException("核心进程已退出；本次任务未确认完成，请检查任务日志"))
            }
            lateinit var connection: ServiceConnection
            val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
                message.data.getString("progress")?.let { onProgress(it); return@Handler true }
                runCatching { JSONObject(requireNotNull(message.data.getString("response"))) }
                    .onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
                if (bound) { bound = false; app.unbindService(connection) }
                true
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    remote = binder
                    try {
                        binder.linkToDeath(death, 0)
                        Messenger(binder).send(Message.obtain().apply {
                            replyTo = receiver; data = Bundle().apply { putString("request", request.toString()) }
                        })
                    } catch (error: Exception) { result.completeExceptionally(error); ended.complete(Unit) }
                }
                override fun onServiceDisconnected(name: ComponentName) { death.binderDied() }
                override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IllegalStateException("无法连接工程核心")); ended.complete(Unit) }
            }
            try {
                bound = app.bindService(Intent(app, CoreWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound) { "无法启动工程核心" }
                val response = withTimeout(180_000) { result.await() }
                withTimeout(10_000) { ended.await() }
                check(response.optBoolean("ok")) { response.optString("error", "工程操作失败") + response.optString("hint").takeIf { it.isNotBlank() && it != "null" }?.let { "\n$it" }.orEmpty() }
                response.getJSONObject("result")
            } catch(error:TimeoutCancellationException) {
                throw IllegalStateException("工程任务超时，未确认完成。请检查 /sdcard/mcpy/logs 中的任务日志后重试",error)
            } finally {
                // A final worker reply may already be queued when the caller is cancelled.
                // Clear ownership before unbinding so that reply cannot unbind a second time.
                if (bound) { bound = false; app.unbindService(connection) }
                if(remote!=null&&!ended.isCompleted)withContext(NonCancellable){withTimeoutOrNull(10_000){ended.await()}}
                remote?.let { runCatching { it.unlinkToDeath(death, 0) } }
            }
        }
    }
}
