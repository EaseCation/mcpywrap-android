package dev.mcpywrap.workspace

import org.json.JSONObject

/** A recovery instruction, not a claim about whether Android blocked or froze a process. */
object UserIntervention {
    private val codes=setOf("host_unavailable","host_service_required","vendor_background_launch_denied","background_launch_unconfirmed",
        "launch_blocked_by_os","game_presentation_pending","game_start_unknown","pip_not_confirmed","pip_permission_missing","overlay_permission_required","device_locked")
    fun attach(value:JSONObject,code:String=value.optString("code")):JSONObject {
        if(code !in codes&&value.optString("state")!="waiting_user"&&value.optString("phase")!="waiting_user")return value
        val message=(if(code=="device_locked")"请先解锁手机。"else "")+
            "请在手机上手动切回 mcpy，继续这次启动或恢复游戏画面；完成后告诉我。"+
            "若希望以后从开发工具直接启动，请在系统权限中检查「后台弹出界面」和画中画。切换应用后连接中断时，请在开发连接页打开省电设置，小米选择「无限制」。"
        val instruction="立即停止自动尝试，把 user_message 告诉用户并等待明确确认。不要自动 run、restart、重新构建、新建世界、循环轮询或尝试其他唤起/权限绕过方式。用户确认后只查询或恢复原任务。"
        value.put("needs_user_action",true).put("next_action","ask_user").put("retryable",false).put("retry_policy","after_user_confirmation")
            .put("user_message",message).put("agent_instruction",instruction)
        val hint=value.optString("hint")
        if(!hint.contains("需要用户操作，请停止自动重试。"))value.put("hint","需要用户操作，请停止自动重试。$message"+if(hint.isBlank())""else " 原因与诊断：$hint")
        value.optJSONArray("actions")?.let{actions->for(i in 0 until actions.length())actions.optJSONObject(i)?.put("actor","user")}
        return value
    }
}
