package dev.mcpywrap.application

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/** Battery policy is advisory. Android's Doze exemption does not prove an OEM won't freeze an app. */
class BackgroundExecutionPolicy(private val app:Application,private val launch:BackgroundLaunchPolicy){
    fun inspect():JSONObject {
        val exempt=runCatching{app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)}.getOrNull()
        val intent=settingsIntent()
        return JSONObject().put("state",if(launch.xiaomi||exempt==null)"unknown"else if(exempt)"ok"else "restricted")
            .put("vendor",if(launch.xiaomi)"xiaomi"else android.os.Build.MANUFACTURER)
            .put("system_optimization_exempt",exempt?:JSONObject.NULL).put("vendor_policy_verified",false)
            .put("hint",if(launch.xiaomi)"若实际出现后台断连，请用户在 mcpy「开发」页打开省电设置，选择「无限制」。这不是运行游戏的前置步骤。"else "若实际出现后台断连，请用户在 mcpy「开发」页检查后台运行设置；正常连接时直接运行游戏。")
            .put("settings_action",intent.action).put("settings_uri",intent.dataString?:JSONObject.NULL).put("actor","user")
    }
    fun settingsIntent():Intent {
        // HyperOS routes this standard action to mcpy's battery detail page with the
        // explicit unrestricted choice. Older Xiaomi devices use their own activity.
        val candidates=mutableListOf<Intent>()
        if(launch.xiaomi){
            candidates.add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+app.packageName)))
            candidates.add(Intent().setClassName("com.miui.powerkeeper","com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name",app.packageName).putExtra("package_label",app.applicationInfo.loadLabel(app.packageManager).toString()))
        }
        candidates.add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",Uri.parse("package:"+app.packageName)))
        candidates.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        candidates.add(appDetailsIntent())
        return candidates.firstOrNull{app.packageManager.resolveActivity(it,0)!=null}?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?:appDetailsIntent()
    }
    fun appDetailsIntent()=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
