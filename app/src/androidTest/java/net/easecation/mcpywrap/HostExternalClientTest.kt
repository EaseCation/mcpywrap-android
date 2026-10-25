package net.easecation.mcpywrap

import android.app.Application
import android.os.Bundle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in fixture for real third-party UIDs. Pairing still requires the App's normal approval UI. */
class HostExternalClientTest {
    @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
    @Test fun serveWhileExternalClientRuns(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("external")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as Application
        val host=HostController.get(app)
        val stop=File("/sdcard/mcpy/logs/external-client-test.stop");stop.delete()
        instrumentation.runOnMainSync{HostService.start(app)}
        val startup=System.currentTimeMillis()+30_000
        while(!host.online&&System.currentTimeMillis()<startup)Thread.sleep(100)
        check(host.online){host.error?:"Host startup timed out"}
        instrumentation.sendStatus(0,Bundle().apply{putString("stream","\nPAIR_CODE="+host.store.newCode()+"\n")})
        try{
            val deadline=System.currentTimeMillis()+1800_000
            while(!stop.exists()&&System.currentTimeMillis()<deadline)Thread.sleep(500)
            check(stop.exists()){ "External client fixture timed out" }
        }finally{HostService.stop(app)}
    }
}
