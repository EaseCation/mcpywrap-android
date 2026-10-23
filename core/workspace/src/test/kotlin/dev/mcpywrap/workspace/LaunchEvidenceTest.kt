package dev.mcpywrap.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchEvidenceTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun foregroundRecoveryDoesNotRewriteBackgroundFailure(){
        var now=1000L;val history=LaunchHistory(File(temp.root,"history")){now}
        history.record("background-attempt",true,"waiting_user","vendor_background_launch_denied","build1")
        now=5000;history.record("foreground-attempt",false,"started","","build1")
        val observation=history.latestBackground("build2")!!
        assertEquals("waiting_user",observation.getString("outcome"));assertEquals(4000L,observation.getLong("age_ms"))
        assertFalse(observation.getBoolean("same_environment"));assertFalse(observation.has("attempt"))
    }
    @Test fun timelineSortsSourcesAndPreservesActualObservationAges(){
        File(temp.root,"app-timeline.jsonl").writeText("{\"stage\":\"request\",\"observed_at\":1000}\n")
        File(temp.root,"game-timeline.jsonl").writeText("{\"stage\":\"running\",\"observed_at\":3000}\n")
        val events=LaunchEvidence.timeline(temp.root,500,5000).getJSONArray("events")
        assertEquals("request",events.getJSONObject(0).getString("stage"))
        assertEquals(2000L,events.getJSONObject(1).getLong("since_previous_ms"))
        assertEquals(2000L,events.getJSONObject(1).getLong("age_ms"))
        assertEquals(2500L,events.getJSONObject(1).getLong("elapsed_ms"))
    }
    @Test fun oldSessionUsesReceiptsWithoutInventingStages(){
        File(temp.root,"bootstrap.json").writeText("{\"stage\":\"activity_constructed\",\"observed_at\":1000}")
        assertEquals(1,LaunchEvidence.timeline(temp.root,500,5000).getJSONArray("events").length())
    }
    @Test fun launchConfirmationAndHeartbeatTimeoutAreIndependent(){
        val waiting=JSONObject().put("phase","launch_pending")
        assertEquals("loading",SessionObservation.resolve(waiting,null,1000,31000,null,launchTimeoutMs=45000).getString("phase"))
        assertEquals("unknown",SessionObservation.resolve(waiting,null,1000,47000,null,launchTimeoutMs=45000).getString("phase"))
        val running=JSONObject().put("phase","running").put("pid",123).put("heartbeat",1000)
        assertEquals("unresponsive",SessionObservation.resolve(running,null,1000,31000,true,launchTimeoutMs=45000).getString("phase"))
    }
}
