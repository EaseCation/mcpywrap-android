package net.easecation.mcpywrap

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.application.ApplicationFailure
import dev.mcpywrap.application.ApplicationOperations
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in recovery regression against an explicitly selected existing project and world. */
class HostPendingLaunchTest {
    @Test fun foregroundReturnContinuesPreparedSession()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pendingLaunch")=="true")
        val app=instrumentation.targetContext.applicationContext as Application
        val application=ApplicationOperations.get(app)
        val repository=application.repository
        check(!repository.active()){ "Do not interrupt an existing game" }
        check(!application.foreground){ "Start this check with mcpy in the background" }
        check(application.diagnostics.background.inspect().getString("state")=="missing"){ "This regression requires an existing denied background permission; never change it from the test" }
        val project=repository.projects().single{it.folder==args.getString("project")}
        val world=repository.worlds(project).single{it.id==args.getString("instance")}
        val worlds=repository.worlds(project).map{it.id}.sorted()
        var session:String?=null
        val evidence=File(repository.root,"logs/pending-launch-device-proof.json")
        try {
            val failure=try {
                application.mutate{application.start(project,world,external=true)}
                error("Denied background launch must return waiting_user")
            } catch(e:ApplicationFailure){e}
            assertEquals("waiting_user",failure.state)
            assertEquals("vendor_background_launch_denied",failure.code)
            assertTrue(failure.details.getBoolean("prepared"))
            assertFalse(failure.details.getBoolean("game_started"))
            session=failure.details.getString("session")
            val launch=File(repository.root,"logs/$session/launch.json")
            fun digest()=MessageDigest.getInstance("SHA-256").digest(launch.readBytes()).joinToString(""){"%02x".format(it)}
            val before=digest()
            val proof=JSONObject().put("session",session).put("instance",world.id).put("waiting",failure.details).put("launch_sha256_before",before)
            evidence.writeText(proof.toString(2))
            assertEquals(0,repository.sessionStatus()!!.getInt("pid"))
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","\nPrepared session retained; opening App to exercise onPostResume\n")})
            // Shell represents the user's explicit return, not an application permission bypass.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -a android.intent.action.VIEW -d mcpy://service/start net.easecation.mcpywrap"
            )).use{it.readBytes()}
            withTimeout(180_000){while(repository.sessionStatus()?.let{it.optString("phase")=="running"&&it.optBoolean("pip")&&it.optBoolean("serverReady")}!=true)delay(250)}
            val running=repository.sessionStatus()!!
            assertEquals(session,File(repository.session()!!.getString("session")).name)
            assertEquals(world.id,repository.session()!!.getString("instanceId"))
            assertEquals(before,digest())
            assertEquals(worlds,repository.worlds(project).map{it.id}.sorted())
            assertTrue(running.getInt("pid")>0)
            assertEquals(running.getInt("renderWidth"),running.getInt("bufferWidth"));assertEquals(running.getInt("renderHeight"),running.getInt("bufferHeight"))
            proof.put("running",running).put("launch_sha256_after",digest()).put("same_worlds",true).put("verified",true)
            evidence.writeText(proof.toString(2))
        } finally {
            if(session!=null&&File(repository.session()?.optString("session").orEmpty()).name==session){
                application.mutate{application.stopCurrent()}
                val proof=if(evidence.isFile)JSONObject(evidence.readText())else JSONObject()
                proof.put("final",repository.sessionStatus());evidence.writeText(proof.toString(2))
            }
        }
    }
}
