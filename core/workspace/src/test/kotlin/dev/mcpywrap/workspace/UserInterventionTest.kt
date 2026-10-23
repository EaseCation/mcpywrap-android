package dev.mcpywrap.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserInterventionTest {
    @Test fun silentLaunchIncludesAnExplicitHumanHandoffWithoutInventingState(){
        val value=UserIntervention.attach(JSONObject().put("phase","unknown").put("code","game_start_unknown").put("session","same"))
        assertTrue(value.getBoolean("needs_user_action"));assertFalse(value.getBoolean("retryable"))
        assertEquals("ask_user",value.getString("next_action"));assertEquals("same",value.getString("session"))
        assertTrue(value.getString("hint").contains("停止自动重试"));assertTrue(value.getString("user_message").contains("手动切回"))
        assertFalse(value.has("prepared"));assertFalse(value.has("game_started"))
    }
    @Test fun nativeCrashDoesNotBecomeAPermissionProblem(){
        val value=UserIntervention.attach(JSONObject().put("phase","failed").put("code","engine_crashed"))
        assertFalse(value.has("needs_user_action"))
    }
    @Test fun repeatedObservationDoesNotDuplicateGuidance(){
        val value=JSONObject().put("state","waiting_user").put("prepared",true).put("game_started",false)
        UserIntervention.attach(value);val hint=value.getString("hint");UserIntervention.attach(value)
        assertEquals(hint,value.getString("hint"));assertFalse(value.getBoolean("game_started"))
    }
}
