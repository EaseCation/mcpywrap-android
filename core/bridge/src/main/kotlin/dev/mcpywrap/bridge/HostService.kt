package dev.mcpywrap.bridge

import android.app.*
import android.content.*
import android.os.IBinder
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.mcpywrap.application.ApplicationFailure
import kotlinx.coroutines.*

/** User-started loopback service. Idle state only has a system notification. */
class HostService:Service(){
    companion object {
        const val OPEN="dev.mcpywrap.OPEN_DEVELOPMENT"
        fun start(app:Application) {
            app.getSharedPreferences("host",0).edit().putBoolean("development-enabled",true).apply()
            ContextCompat.startForegroundService(app,Intent(app,HostService::class.java))
        }
        fun stop(app:Application,userInitiated:Boolean=true):Boolean {
            if(userInitiated)app.getSharedPreferences("host",0).edit().putBoolean("development-enabled",false).apply()
            return app.stopService(Intent(app,HostService::class.java))
        }
        fun canResume(app:Application)=app.getSharedPreferences("host",0).getBoolean("development-enabled",false)
        fun openIntent(context:Context)=context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var host:HostController
    private var overlay:HostOverlay?=null
    private var overlayKey=""
    private var monitor:Job?=null
    private var destroyed=false
    private var foregroundPair:String?=null
    private var waitingLaunch:String?=null
    private var notificationsEnabled=false
    private val dismissed=mutableSetOf<String>()
    private val notified=mutableSetOf<String>()
    private val launchText=mutableStateOf("")
    private val manager get()=getSystemService(NotificationManager::class.java)
    private val locked get()=getSystemService(KeyguardManager::class.java).isKeyguardLocked
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();host=HostController.get(application as Application)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){getSharedPreferences("host",0).edit().putBoolean("development-enabled",false).apply();stopSelf();return START_NOT_STICKY}
        manager.createNotificationChannel(NotificationChannel("mcpy-host","开发连接状态",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("mcpy-pair","连接确认",NotificationManager.IMPORTANCE_HIGH))
        foregroundPair=host.store.pendingPairs().firstOrNull()?.getString("id")
        startForeground(17653,serviceNotification(foregroundPair))
        host.gameLauncher={block->
            try{
                withContext(Dispatchers.Main){
                val canShow=Settings.canDrawOverlays(this@HostService)&&!locked&&host.application.diagnostics.background.inspect().optString("state")!="missing"
                if(canShow){
                    host.launchProgress.value="正在准备作品"
                    syncPresentation()
                    // A missing presentation is deferred by the application layer, not a reason
                    // to discard a prepared operation or to create a second world.
                    withTimeoutOrNull(3000){while(overlay?.visible!=true)delay(16)}
                }
            }
                block()
            }finally{withContext(NonCancellable+Dispatchers.Main){host.launchProgress.value=null;syncPresentation()}}
        }
        if(monitor==null)monitor=scope.launch{while(isActive){syncPresentation();delay(250)}}
        scope.launch{try{host.start()}catch(_:Exception){stopSelf()}}
        return START_NOT_STICKY
    }
    private fun confirmationIntent(id:String):PendingIntent {
        val confirm=Intent(this,PairingConfirmationActivity::class.java).putExtra("id",id)
            .setData(android.net.Uri.parse("mcpy-pair://request/$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this,id.hashCode(),confirm,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun serviceNotification(pair:String?):Notification {
        val open=pair?.let{confirmationIntent(it)}?:PendingIntent.getActivity(this,0,openIntent(this),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,HostService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"mcpy-host").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if(pair!=null)"mcpy 有连接请求等待确认"else if(waitingLaunch!=null)"作品已准备，点此继续启动"else "mcpy 开发连接已开启")
            .setContentText(if(waitingLaunch!=null&&pair==null)"打开 mcpy 继续同一会话，不会重新构建或新建世界"else if(pair==null)"已连接的工具可以开发作品；游戏按需显示小窗"else "点击确认，完成后自动返回原应用")
            .setContentIntent(open).setOngoing(true).setGroup("mcpy-service").setOnlyAlertOnce(true)
            .addAction(0,"停止连接",stop).build()
    }
    private fun syncPresentation(){
        if(destroyed)return
        val canNotify=manager.areNotificationsEnabled()
        if(canNotify&&!notificationsEnabled){notified.clear();foregroundPair=null}
        notificationsEnabled=canNotify
        val pending=host.store.pendingPairs()
        val first=pending.firstOrNull()?.getString("id")
        val launch=host.application.pendingLaunch()?.takeIf{it.optString("state") in listOf("waiting_user","presentation_waiting")}?.getString("session")
        if(first!=foregroundPair||launch!=waitingLaunch){foregroundPair=first;waitingLaunch=launch;runCatching{manager.notify(17653,serviceNotification(first))}}
        val ids=pending.map{it.getString("id")}.toSet()
        for(id in notified-ids){manager.cancel(id,17654);notified.remove(id);dismissed.remove(id)}
        for(pair in pending){
            val id=pair.getString("id")
            if(canNotify&&notified.add(id)){
                val open=confirmationIntent(id)
                fun action(allow:Boolean):NotificationCompat.Action {
                    val intent=Intent(this,PairingActionReceiver::class.java).setAction(if(allow)"allow" else "deny")
                        .setData(android.net.Uri.parse("mcpy-pair://request/$id")).putExtra("id",id)
                    val click=PendingIntent.getBroadcast(this,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    return NotificationCompat.Action.Builder(0,if(allow)"允许连接" else "拒绝",click).setAuthenticationRequired(true).build()
                }
                val text="允许编辑 mcpy 中的作品、构建模组和调试游戏。仅允许你刚发起的连接。"
                val notification=NotificationCompat.Builder(this,"mcpy-pair").setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("${pair.getString("label")} 请求连接 mcpy").setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setGroup("mcpy-pair-$id")
                    .addAction(action(false)).addAction(action(true)).build()
                runCatching{manager.notify(id,17654,notification)}
            }
        }
        val progress=host.launchProgress.value
        val pair=pending.firstOrNull{it.getString("id") !in dismissed}
        val key=when{
            locked||!Settings.canDrawOverlays(this)->""
            progress!=null->"launch"
            !host.foreground&&!host.confirmationShowing&&pair!=null->pair.getString("id")
            else->""
        }
        launchText.value=progress.orEmpty()
        if(key==overlayKey)return
        overlay?.close();overlay=null;overlayKey=""
        if(key.isEmpty())return
        runCatching{
            HostOverlay(this){
                if(key=="launch")LaunchCard(launchText.value)
                else if(pair!=null)ConnectionCard(pair.getString("label"),pair.getString("access"),
                    onAllow={decide(key,true)},onDeny={decide(key,false)},onLater={dismissed.add(key);syncPresentation()})
            }
        }.onSuccess{overlay=it;overlayKey=key}
    }
    private fun decide(id:String,allow:Boolean){
        if(!locked)runCatching{host.approvePair(id,allow)}
        syncPresentation()
    }
    override fun onDestroy(){
        destroyed=true
        host.gameLauncher=null;host.launchProgress.value=null
        host.stop();scope.cancel();monitor=null
        overlay?.close();overlay=null
        notified.forEach{manager.cancel(it,17654)}
        super.onDestroy()
    }
}

/** Non-exported. Only the immutable, request-specific notification PendingIntent can call it. */
class PairingActionReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)return
        if(intent.action !in listOf("allow","deny"))return
        val id=intent.getStringExtra("id")?:return
        runCatching{HostController.get(context.applicationContext as Application).approvePair(id,intent.action=="allow")}
    }
}
