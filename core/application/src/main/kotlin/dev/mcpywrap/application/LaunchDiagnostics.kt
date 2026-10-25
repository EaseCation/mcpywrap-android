package dev.mcpywrap.application

import android.app.*
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import dev.mcpy.runtime.RuntimeRepository
import dev.mcpywrap.workspace.WorkspaceRepository
import dev.mcpywrap.workspace.LaunchEvidence
import dev.mcpywrap.workspace.LaunchHistory
import dev.mcpywrap.workspace.UserIntervention
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

class LaunchDiagnostics(private val app:Application,private val repository:WorkspaceRepository){
    val background=BackgroundLaunchPolicy(app)
    val execution=BackgroundExecutionPolicy(app,background)
    val history=LaunchHistory(File(app.noBackupFilesDir,"launch-observations.json"))
    val environment get()=android.os.Build.FINGERPRINT+":"+app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime

    fun preflight(external:Boolean=true,pip:Boolean=true):JSONObject {
        val checks=JSONObject();val blockers=JSONArray()
        fun check(name:String,ok:Boolean,code:String,hint:String,settings:String?=null){
            checks.put(name,JSONObject().put("state",if(ok)"ok"else "missing").put("hint",if(ok)""else hint)
                .put("settings_action",settings?:JSONObject.NULL).put("settings_uri",if(settings!=null)"package:"+app.packageName else JSONObject.NULL))
            if(!ok)blockers.put(JSONObject().put("code",code).put("hint",hint).put("check",name))
        }
        check("workspace",Environment.isExternalStorageManager(),"workspace_permission_missing","在 mcpy 中允许所有文件访问",Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        check("runtime",RuntimeRepository(app).installed()!=null,"runtime_not_installed","在 mcpy 设置中安装游戏运行环境")
        check("unlocked",!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked,"device_locked","解锁手机后重试")
        if(external)check("overlay",Settings.canDrawOverlays(app),"overlay_permission_required","系统当前不允许临时悬浮提示；先收起安全键盘或切回 mcpy，持续受限时检查显示在其他应用上层权限",Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        if(pip){
            check("pip_supported",app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)&&(!external||android.os.Build.VERSION.SDK_INT>=33),"pip_unsupported","当前系统不支持所请求的游戏画中画启动方式")
            val mode=runCatching{app.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,app.applicationInfo.uid,app.packageName)}.getOrNull()
            check("pip_permission",mode !in listOf(AppOpsManager.MODE_IGNORED,AppOpsManager.MODE_ERRORED),"pip_permission_missing","在系统特殊应用权限中允许 mcpy 使用画中画","android.settings.PICTURE_IN_PICTURE_SETTINGS")
            if(mode==null||mode==AppOpsManager.MODE_DEFAULT)checks.getJSONObject("pip_permission").put("state","unknown")
        }
        val policy=background.inspect()
        history.latestBackground(environment)?.let{policy.put("last_observation",it)}
        checks.put("background_launch",if(external)policy else JSONObject().put("state","not_required"))
        if(external&&policy.getString("state")=="missing")blockers.put(JSONObject().put("code","vendor_background_launch_denied").put("check","background_launch").put("hint",policy.getString("hint")))
        return JSONObject().put("ready",blockers.length()==0).put("checks",checks).put("blockers",blockers)
            .put("observed_at",System.currentTimeMillis()).put("recovery_uri","mcpy://service/start")
    }
    fun requireReady(external:Boolean,pip:Boolean,allowDeferred:Boolean=false){
        val result=preflight(external,pip)
        val blockers=result.getJSONArray("blockers")
        val first=(0 until blockers.length()).map{blockers.getJSONObject(it)}.firstOrNull{
            !allowDeferred||it.getString("code") !in listOf("device_locked","overlay_permission_required","vendor_background_launch_denied","pip_permission_missing")
        }
        if(first!=null){
            throw ApplicationFailure(first.getString("code"),first.getString("hint"),details=JSONObject().put("launch_preconditions",result).put("hint",first.getString("hint")))
        }
    }
    fun observe(record:JSONObject):JSONObject {
        val value=repository.sessionStatus(record)?:JSONObject().put("phase","unknown")
        val directory=WorkspaceRepository.inside(File(repository.root,"logs"),File(record.getString("session")).name)
        value.put("launch_preconditions",preflight(record.optBoolean("launch_external",true),record.optBoolean("small_window",true)))
        value.put("instance_id",record.optString("instanceId")).put("project",record.optString("project"))
        val pid=value.optInt("pid")
        if(pid>0&&value.opt("process_alive")==false){
            val exit=runCatching{app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName,pid,8)
                .firstOrNull{it.processName==app.packageName+":game"&&it.timestamp>=value.optLong("created_at")}}.getOrNull()
            if(exit!=null){
                value.put("process_exit",JSONObject().put("reason",exit.reason).put("status",exit.status).put("timestamp",exit.timestamp).put("description",exit.description))
                if(exit.reason in listOf(ApplicationExitInfo.REASON_CRASH,ApplicationExitInfo.REASON_CRASH_NATIVE))value.put("code","engine_crashed")
            }
        }
        value.put("engine_log_tail",tail(File(directory,"runtime.log")))
        val now=System.currentTimeMillis()
        value.put("launch_timeline",LaunchEvidence.timeline(directory,record.optLong("created_at"),now))
        value.put("launch_timeout_seconds",record.optInt("launch_timeout_seconds",20)).put("world_load_timeout_seconds",300)
        if(record.has("previous_session"))value.put("previous_session",record.getString("previous_session")).put("session_changed",true)
        value.optJSONObject("bootstrap")?.let{entry->if(entry.has("observed_at"))entry.put("age_ms",(now-entry.getLong("observed_at")).coerceAtLeast(0))}
        val receipt=File(directory,"launch-receipt.json")
        if(receipt.isFile)runCatching{val entry=JSONObject(receipt.readText());if(entry.has("observed_at"))entry.put("age_ms",(now-entry.getLong("observed_at")).coerceAtLeast(0));value.put("launch_receipt",entry)}
        value.put("hint",when(value.optString("phase")){
            "waiting_user"->"作品已准备好，请用户手动打开 mcpy://service/start 继续同一个会话，或在设置中允许后台弹出界面；不要重新 run --new。"
            "unknown"->"先检查启动前置条件和启动记录；需要恢复时请用户手动打开 mcpy://service/start，不由工具自动唤起。不要重复 run --new；恢复后继续原实例。"
            "unresponsive"->"进程状态未更新，检查引擎日志与退出记录；不要重放已发送的代码或启动操作。"
            "failed","interrupted"->"修复已报告原因后，使用原实例 ID 重新运行，不需要新建世界。"
            else->""
        })
        if(value.optBoolean("session_changed"))value.put("hint",(value.optString("hint")+" 后续日志、截图和调试请使用 --session "+directory.name+"；旧会话记录仍保留。").trim())
        return UserIntervention.attach(value)
    }
    private fun tail(file:File):String {
        if(!file.isFile)return ""
        return runCatching{RandomAccessFile(file,"r").use{f->f.seek((f.length()-8192).coerceAtLeast(0));val bytes=ByteArray((f.length()-f.filePointer).toInt());f.readFully(bytes);bytes.toString(Charsets.UTF_8).lineSequence().toList().takeLast(40).joinToString("\n")}}.getOrDefault("")
    }
}
