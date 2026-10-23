package dev.mcpywrap.android

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.application.ApplicationOperations
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import dev.mcpywrap.bridge.HostController
import dev.mcpywrap.bridge.HostService
import java.io.File

/** Explicit on-device launch regression; only the dedicated USB-HostProof fixture. */
class HostDirectPipTest {
    @Test fun launchWithCallerVisibleAndLoadingInPip()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as Application
        val application=ApplicationOperations.get(app)
        val repository=application.repository
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","\nInspecting dedicated QA project\n")})
        val project=repository.projects().single{it.folder=="USB-HostProof"}
        check(!repository.active()){ "Do not interrupt an existing game" }
        val host=HostController.get(app)
        val previouslyEnabled=HostService.canResume(app)
        withTimeout(60_000){while(!host.foreground)delay(100)}
        instrumentation.runOnMainSync{HostService.start(app)}
        withTimeout(30_000){while(!host.online)delay(100)}
        val callerReady=File(repository.root,"logs/direct-pip-caller-ready")
        callerReady.delete()
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","READY_FOR_CALLER\n")})
        withTimeout(60_000){while(!callerReady.exists())delay(100)}
        var started=false
        try {
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","Launching directly into PiP\n")})
            val result=application.mutate{host.gameLauncher!!.invoke{application.start(project,external=true)}}
            started=true
            assertEquals("loading",result.getString("state"))
            val initial=repository.sessionStatus()!!
            assertTrue("External launch must report actual PiP before returning: $initial",initial.getBoolean("pip"))
            assertEquals("direct_pip",initial.getString("launchMode"))
            assertTrue(initial.optString("loadingStage").isNotBlank())
            val evidence=File(repository.root,"logs/direct-pip-phone").apply{mkdirs()}
            File(evidence,"initial.json").writeText(initial.toString())
            withTimeout(180_000){while(repository.sessionStatus()?.optString("phase")!="running")delay(250)}
            val status=repository.sessionStatus()!!
            assertTrue(status.getBoolean("pip"));assertTrue(status.getBoolean("serverReady"))
            assertEquals(1920,status.getInt("bufferWidth"));assertEquals(1080,status.getInt("bufferHeight"))
            assertTrue(status.getInt("viewWidth")<1920)
            File(evidence,"running.json").writeText(status.toString())
            val screenshot=application.mutate{application.game(project,result.getString("session"),"screenshot")}
            assertEquals(1920,screenshot.getInt("width"));assertEquals(1080,screenshot.getInt("height"))
            File(evidence,"capture.json").writeText(screenshot.toString())
            delay(5000)
        } finally {
            if((started||repository.session()?.optString("projectId")==project.id)&&repository.sessionStatus()?.optInt("pid",0)!=0&&repository.active()){
                repository.requestStop()
                withTimeout(90_000){while(repository.active())delay(250)}
                assertEquals("saved",repository.sessionStatus()?.optString("phase"))
            }
            HostService.stop(app,userInitiated=false);app.getSharedPreferences("host",0).edit().putBoolean("development-enabled",previouslyEnabled).apply()
        }
    }
}
