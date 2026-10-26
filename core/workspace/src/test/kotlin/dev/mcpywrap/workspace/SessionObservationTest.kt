package dev.mcpywrap.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionObservationTest {
    @Test fun retainedPreparationIsWaitingNotRunningAndCanExpire(){
        val handoff=JSONObject().put("state","waiting_user").put("expires_at",5000).put("reason","vendor_background_launch_denied")
        val raw=JSONObject().put("phase","launch_pending")
        val pending=SessionObservation.resolve(raw,null,1000,2000,null,handoff)
        assertEquals("waiting_user",pending.getString("phase"));assertTrue(pending.isNull("heartbeat"))
        val expired=SessionObservation.resolve(raw,null,1000,6000,null,handoff)
        assertEquals("cancelled",expired.getString("phase"))
        val running=SessionObservation.resolve(JSONObject().put("phase","running").put("pid",42).put("heartbeat",6000),null,1000,6100,true,handoff)
        assertEquals("running",running.getString("phase"))
    }
    @Test fun initialDeploymentIsPendingAndHasNoHeartbeat(){
        val raw=JSONObject().put("phase","launch_pending").put("stage","deployed").put("created_at",1000)
        val result=SessionObservation.resolve(raw,null,1000,2000,null)
        assertEquals("launch_pending",result.getString("reported_phase"))
        assertEquals("loading",result.getString("phase"));assertTrue(result.isNull("heartbeat"))
        assertTrue(result.getBoolean("recent_evidence"))
    }
    @Test fun feedbackFrozenDeploymentTimestampIsNotAGameHeartbeat(){
        val created=1792724055512L
        val raw=JSONObject().put("phase","loading").put("heartbeat",created)
        val result=SessionObservation.resolve(raw,null,created,created+600_000,null)
        assertEquals("unknown",result.getString("phase"))
        assertEquals("loading",result.getString("reported_phase"))
        assertTrue(result.isNull("heartbeat"));assertTrue(result.isNull("process_alive"))
        assertEquals("game_start_unknown",result.getString("code"))
        assertEquals(created,raw.getLong("heartbeat")) // Observation never rewrites engine evidence.
    }
    @Test fun processConfirmedBeforeNativeActivityHasNoFakeHeartbeat(){
        val bootstrap=JSONObject().put("pid",42).put("observed_at",1000).put("stage","loading_apk_classes")
        val result=SessionObservation.resolve(JSONObject().put("phase","loading"),bootstrap,900,2000,true)
        assertEquals("loading",result.getString("phase"));assertEquals(42,result.getInt("pid"))
        assertTrue(result.isNull("heartbeat"));assertEquals("loading_apk_classes",result.getString("loadingStage"))
    }
    @Test fun liveProcessWithStalledHeartbeatIsNotCalledCrashed(){
        val result=SessionObservation.resolve(JSONObject().put("phase","running").put("pid",42).put("heartbeat",1000),null,500,50_000,true)
        assertEquals("unresponsive",result.getString("phase"));assertEquals("game_heartbeat_stale",result.getString("code"))
        assertEquals(49_000,result.getLong("heartbeat_age_ms"))
    }
    @Test fun confirmedExitWinsOverFreshRunningRecord(){
        val result=SessionObservation.resolve(JSONObject().put("phase","running").put("pid",42).put("heartbeat",1000),null,500,1100,false)
        assertEquals("interrupted",result.getString("phase"));assertEquals("engine_exited",result.getString("code"))
    }
    @Test fun savedSessionDoesNotBecomeFailureWhenItsProcessExits(){
        val evidence=JSONObject().put("observed_at",900).put("confirmed",true)
        val raw=JSONObject().put("phase","saved").put("pid",42).put("heartbeat",1000).put("exit_evidence",evidence)
        val result=SessionObservation.resolve(raw,null,500,99_000,false)
        assertEquals("saved",result.getString("phase"))
        assertEquals(98_100,result.getJSONObject("exit_evidence").getLong("age_ms"))
        assertFalse(evidence.has("age_ms"))
    }
    @Test fun bootstrapFailureIsAvailableBeforeEngineHeartbeat(){
        val bootstrap=JSONObject().put("phase","failed").put("pid",42).put("observed_at",1000).put("error","APK missing")
        val result=SessionObservation.resolve(JSONObject().put("phase","loading"),bootstrap,500,1100,false)
        assertEquals("failed",result.getString("phase"));assertEquals("APK missing",result.getString("error"))
    }
}
