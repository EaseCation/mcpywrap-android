package dev.mcpywrap.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingLaunchStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val id="session-00000000-0000-0000-0000-000000000001"
    private fun record(logs:File)=JSONObject().put("session",File(logs,id).path).put("projectId","project").put("instanceId","world")
    @Test fun waitingSurvivesRestartAndResumeKeepsSessionButInvalidatesOldAttempt(){
        val logs=temp.newFolder("logs");val file=File(temp.newFolder("private"),"launch.json")
        val store=PendingLaunchStore(file,logs){1000L};val first=store.create(record(logs),JSONObject().put("client_id","client").put("operation_id","operation"))
        store.transition(id,"waiting_user","vendor_background_launch_denied","open app")
        assertThrows(IllegalArgumentException::class.java){store.create(record(logs))}
        val resumed=PendingLaunchStore(file,logs){2000L}.retry(id)
        assertEquals(id,resumed.getString("session"));assertEquals("world",resumed.getString("instance_id"))
        assertEquals("operation",resumed.getString("operation_id"));assertNotEquals(first.getString("attempt"),resumed.getString("attempt"))
        val public=File(logs,"$id/handoff.json").readText()
        assertFalse(public.contains("attempt"));assertFalse(public.contains("client_id"))
    }
    @Test fun cancellationCannotBeResumed(){
        val logs=temp.newFolder("logs");val store=PendingLaunchStore(File(temp.newFolder("private"),"launch.json"),logs){1000L}
        store.create(record(logs));store.transition(id,"cancelled")
        assertNull(store.pending());assertThrows(IllegalStateException::class.java){store.retry(id)}
    }
    @Test fun onlyUnstartedWaitExpires(){
        var now=1000L;val logs=temp.newFolder("logs");val store=PendingLaunchStore(File(temp.newFolder("private"),"launch.json"),logs){now}
        store.create(record(logs));store.transition(id,"waiting_user");now+=16*60_000
        assertNull(store.pending());assertEquals("expired",store.read()!!.getString("state"))
        store.create(record(logs));store.transition(id,"presentation_waiting");now+=16*60_000
        assertNotNull(store.pending()) // A window wait must not terminate an existing game.
    }
    @Test fun lateContinuationCannotChangeAnotherSession(){
        val logs=temp.newFolder("logs");val store=PendingLaunchStore(File(temp.newFolder("private"),"launch.json"),logs){1000L}
        store.create(record(logs));assertThrows(IllegalArgumentException::class.java){store.transition("session-other","started")}
        assertEquals(id,store.read()!!.getString("session"))
    }
}
