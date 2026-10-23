package dev.mcpywrap.android

import android.app.Application
import android.os.Bundle
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real UI fixture. Harness taps the overlay and notification actions, never auto-approves. */
class HostPairingExperienceTest {
    @Test fun approveWithoutLeavingCaller()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as Application
        val host=HostController.get(app)
        val previouslyEnabled=HostService.canResume(app)
        val marker=File("/sdcard/mcpy/logs/pairing-ui-ready")
        val owned=mutableListOf<String>()
        val requested=mutableListOf<String>()
        fun signal(text:String){instrumentation.sendStatus(0,Bundle().apply{putString("stream","\n$text\n")})}
        suspend fun waitCaller(signal:String){marker.delete();signal(signal);withTimeout(120_000){while(!marker.exists())delay(100)}}
        withTimeout(60_000){while(!host.foreground)delay(100)}
        instrumentation.runOnMainSync{HostService.start(app)}
        withTimeout(30_000){while(!host.online)delay(100)}
        try{
            for(mode in listOf("OVERLAY","NOTIFICATION")){
                waitCaller("READY_$mode")
                assertEquals(mode=="OVERLAY",Settings.canDrawOverlays(app))
                val pending=host.store.beginPair(JSONObject().put("code",host.store.newCode()).put("access","files")
                    .put("request_id",UUID.randomUUID().toString().replace("-","")).put("label","QA 连接验证"))
                requested.add(pending.getString("id"));host.changed();signal("CONFIRM_$mode")
                var result=JSONObject()
                withTimeout(120_000){while(true){result=host.store.pairResult(pending.getString("id"),JSONObject().put("secret",pending.getString("secret")));if(result.getString("state")=="approved")break;delay(100)}}
                val client=result.getString("client_id");owned.add(client)
                assertEquals(client,host.store.authenticate("Bearer "+result.getString("token")))
                host.revoke(client);owned.remove(client)
                signal("APPROVED_$mode")
                delay(750)
            }
        }finally{host.store.pendingPairs().filter{it.getString("id") in requested}.forEach{host.approvePair(it.getString("id"),false)};owned.forEach{host.revoke(it)};HostService.stop(app,userInitiated=false);app.getSharedPreferences("host",0).edit().putBoolean("development-enabled",previouslyEnabled).apply();marker.delete()}
    }
}
