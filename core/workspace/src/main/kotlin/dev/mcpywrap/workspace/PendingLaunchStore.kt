package dev.mcpywrap.workspace

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private handoff metadata. Public files remain the authoritative project/world contents. */
class PendingLaunchStore(private val file:File,private val logs:File,private val now:()->Long={System.currentTimeMillis()}) {
    @Synchronized fun read():JSONObject?=if(file.isFile)JSONObject(file.readText())else null
    private fun save(value:JSONObject):JSONObject {
        WorkspaceRepository.atomic(file,value.toString().toByteArray())
        val mirror=JSONObject().put("state",value.getString("state")).put("expires_at",value.getLong("expires_at"))
            .put("reason",value.optString("reason")).put("hint",value.optString("hint"))
        WorkspaceRepository.atomic(File(WorkspaceRepository.inside(logs,value.getString("session")),"handoff.json"),mirror.toString().toByteArray())
        return value
    }
    @Synchronized fun pending():JSONObject? {
        val value=read()?:return null
        if(value.getString("state") !in listOf("dispatching","waiting_user","presentation_waiting"))return null
        if(value.getString("state")!="presentation_waiting"&&now()>=value.getLong("expires_at")){save(value.put("state","expired"));return null}
        return value
    }
    @Synchronized fun create(record:JSONObject,owner:JSONObject?=null):JSONObject {
        require(pending()==null){"已有启动等待处理，请先继续或取消"}
        val session=File(record.getString("session")).name
        require(session.matches(Regex("session-[a-f0-9-]{36}")))
        return save(JSONObject().put("session",session).put("project_id",record.getString("projectId"))
            .put("instance_id",record.getString("instanceId")).put("state","dispatching")
            .put("created_at",now()).put("expires_at",now()+15*60_000)
            .put("attempt",UUID.randomUUID().toString()).apply{if(owner!=null){put("operation_id",owner.getString("operation_id"));put("client_id",owner.getString("client_id"))}})
    }
    @Synchronized fun transition(session:String,state:String,reason:String="",hint:String=""):JSONObject {
        val value=read()?:error("启动任务不存在")
        require(value.getString("session")==session){"启动任务已被替换"}
        return save(value.put("state",state).put("reason",reason).put("hint",hint))
    }
    @Synchronized fun retry(session:String):JSONObject {
        val value=pending()?:error("启动已过期，请重新运行原实例")
        require(value.getString("session")==session&&value.getString("state")=="waiting_user")
        return save(value.put("state","dispatching").put("attempt",UUID.randomUUID().toString()))
    }
}
