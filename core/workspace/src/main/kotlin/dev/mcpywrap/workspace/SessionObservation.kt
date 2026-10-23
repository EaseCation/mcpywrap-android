package dev.mcpywrap.workspace

import org.json.JSONObject

/** Read-only interpretation. Observation time is never substituted for a game's heartbeat. */
object SessionObservation {
    fun resolve(raw:JSONObject,bootstrap:JSONObject?,created:Long,now:Long,alive:Boolean?,handoff:JSONObject?=null,launchTimeoutMs:Long=20_000):JSONObject {
        val value=JSONObject(raw.toString())
        val reported=raw.optString("phase","unknown")
        if(reported=="launch_pending")value.put("phase","loading")
        val pid=raw.optInt("pid",bootstrap?.optInt("pid",0)?:0)
        val heartbeat=raw.optLong("heartbeat").takeIf{raw.optInt("pid")>0&&it>0}
        val since=heartbeat?:bootstrap?.optLong("observed_at")?.takeIf{it>0}?:if(pid==0&&reported !in listOf("loading","launch_pending")&&raw.has("heartbeat"))raw.optLong("heartbeat")else created
        val age=if(since>0)(now-since).coerceAtLeast(0)else null
        val evidenceWindow=if(pid==0)launchTimeoutMs else 20_000L
        value.put("reported_phase",reported).put("observed_at",now).put("pid",pid)
            .put("process_alive",alive?:JSONObject.NULL).put("heartbeat",heartbeat?:JSONObject.NULL)
            .put("heartbeat_age_ms",heartbeat?.let{(now-it).coerceAtLeast(0)}?:JSONObject.NULL)
            .put("recent_evidence",age!=null&&age<=evidenceWindow).put("evidence_age_ms",age?:JSONObject.NULL)
            .put("created_at",created).put("elapsed_ms",if(created>0)(now-created).coerceAtLeast(0)else JSONObject.NULL)
        if(bootstrap!=null)value.put("bootstrap",bootstrap)
        if(reported !in listOf("saved","failed","cancelled")){
            when {
                bootstrap?.optString("phase")=="failed"->value.put("phase","failed").put("code","engine_bootstrap_failed").put("error",bootstrap.optString("error"))
                pid>0&&alive==false->value.put("phase","interrupted").put("code","engine_exited").put("error","游戏进程已退出，尚未确认正常保存")
                age==null||age>evidenceWindow||(pid==0&&reported !in listOf("loading","launch_pending"))->{
                    if(pid==0)value.put("phase","unknown").put("code","game_start_unknown").put("error","没有收到游戏进程确认，不能据此断定是系统拦截")
                    else value.put("phase","unresponsive").put("code","game_heartbeat_stale").put("error","游戏状态未更新，请检查进程与引擎日志")
                }
                pid>0&&heartbeat==null->value.put("phase","loading").put("loadingStage",bootstrap?.optString("stage","正在初始化游戏进程"))
            }
        }
        if(pid==0&&handoff!=null&&reported !in listOf("saved","failed","cancelled")){
            when(handoff.optString("state")){
                "waiting_user","dispatching"->if(now>=handoff.optLong("expires_at"))value.put("phase","cancelled").put("code","launch_expired").put("error","等待启动已过期，作品和世界仍保留")
                    else if(handoff.optString("state")=="waiting_user")value.put("phase","waiting_user").put("code",handoff.optString("reason")).put("error","").put("hint",handoff.optString("hint"))
                "cancelled","expired"->value.put("phase","cancelled").put("code",if(handoff.optString("state")=="expired")"launch_expired"else "launch_cancelled").put("error","")
            }
        }
        value.put("state",value.optString("phase","unknown"))
        return value
    }
}
