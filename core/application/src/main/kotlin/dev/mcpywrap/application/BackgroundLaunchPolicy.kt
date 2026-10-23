package dev.mcpywrap.application

import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** Vendor policy is isolated from project logic. Failed/blocked reflection means unknown, not allowed. */
class BackgroundLaunchPolicy(private val app:Application){
    val xiaomi get()=Build.MANUFACTURER.lowercase() in listOf("xiaomi","redmi","poco")||Build.BRAND.lowercase() in listOf("xiaomi","redmi","poco")
    fun inspect():JSONObject {
        val mode=if(xiaomi)runCatching{
            AppOpsManager::class.java.getMethod("checkOpNoThrow",Integer.TYPE,Integer.TYPE,String::class.java)
                .invoke(app.getSystemService(AppOpsManager::class.java),10021,app.applicationInfo.uid,app.packageName) as Int
        }.getOrNull()else null
        val state=when(mode){AppOpsManager.MODE_ALLOWED->"ok";AppOpsManager.MODE_IGNORED,AppOpsManager.MODE_ERRORED->"missing";else->"unknown"}
        return JSONObject().put("state",state).put("vendor",if(xiaomi)"xiaomi"else Build.MANUFACTURER)
            .put("hint",if(xiaomi)"在 mcpy 的权限管理 → 其他权限中允许「后台弹出界面」；也可切回 mcpy 继续本次启动"else "系统可能限制后台打开界面；切回 mcpy 即可继续本次启动")
            .put("settings_action",if(xiaomi)"miui.intent.action.APP_PERM_EDITOR"else Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .put("settings_package",app.packageName).put("recovery_uri","mcpy://service/start")
            .put("evidence",if(mode==null)"not_queryable"else "self_appop_10021")
    }
    fun settingsIntent():Intent {
        if(xiaomi){
            val editor=Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter","com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname",app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(app.packageManager.resolveActivity(editor,0)!=null)return editor
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
