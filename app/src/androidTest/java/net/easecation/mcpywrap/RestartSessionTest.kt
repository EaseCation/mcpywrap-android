package net.easecation.mcpywrap

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.application.ApplicationOperations
import dev.mcpywrap.studio.StudioViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RestartSessionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun waitFor(label:String,seconds:Int=90,check:()->Boolean){val end=System.currentTimeMillis()+seconds*1000;while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(250)};error("Timed out: $label")}
    @Test fun appRestartsExternalSessionWithSameWorldAndRejectsStaleOrDeletedTargets(){
        val app=compose.activity.application
        val operations=ApplicationOperations.get(app);val repo=operations.repository
        check(!repo.active()){ "Do not interrupt an existing user game" }
        var vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        waitFor("workspace ready"){vm.state.value.workspaceLoaded&&!vm.state.value.busy}
        val project=repo.createProject("QA-Restart-${System.currentTimeMillis()}")
        val instance=repo.defaultWorld(project)
        var trash:String?=null
        try {
            runBlocking{operations.mutate{operations.start(project,instance,external=true)}}
            waitFor("external game running",180){repo.sessionStatus()?.let{it.optString("phase")=="running"&&it.optBoolean("serverReady")}==true}
            // Model the user returning from an external tool; a new app may be
            // suspended by the vendor while only its game process is visible.
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x30020000 -n ${app.packageName}/.MainActivity"
            ).use{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use{it.readBytes()}}
            vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
            val first=repo.session()!!;val old=first.getString("session")
            assertTrue(first.getBoolean("launch_external"))
            waitFor("restart card ready"){vm.state.value.session==old&&vm.state.value.canRestart&&!vm.state.value.busy}
            compose.runOnUiThread{vm.closeLogs()}
            compose.onNodeWithTag("restart-session").performClick()
            compose.runOnUiThread{vm.restartCurrentSession();vm.restartCurrentSession()}
            waitFor("restart completes",180){!vm.state.value.restartBusy&&!vm.state.value.busy&&repo.session()?.optString("session")!=old}
            assertNull(vm.state.value.error)
            val next=repo.session()!!;val session=next.getString("session")
            assertEquals(instance.id,next.getString("instanceId"));assertEquals(project.id,next.getString("projectId"))
            assertEquals(first.getString("data"),next.getString("data"));assertEquals(File(old).name,next.getString("previous_session"))
            assertEquals("saved",JSONObject(File(old,"status.json").readText()).getString("phase"))
            assertEquals(1,repo.worlds(project).size)
            val sessions=File(repo.root,"logs").listFiles().orEmpty().count{dir->runCatching{JSONObject(File(dir,"launch.json").readText()).optString("projectId")==project.id}.getOrDefault(false)}
            assertEquals("Rapid repeat taps must produce only one restart",2,sessions)
            waitFor("restarted game ready",180){repo.sessionStatus()?.let{it.optString("phase")=="running"&&it.optBoolean("pip")&&it.optBoolean("serverReady")}==true}
            assertEquals(session,vm.state.value.logSession)
            val pid=repo.sessionStatus()!!.getInt("pid")
            val stale=runCatching{runBlocking{operations.mutate{operations.restartCurrentSession(old)}}}.exceptionOrNull()
            assertNotNull("Stale requests must not stop the new game",stale)
            assertEquals(pid,repo.sessionStatus()!!.getInt("pid"))
            val evidence=File(repo.root,"logs/product-evidence").apply{mkdirs()}
            File(evidence,"restart-same-instance.json").writeText(JSONObject().put("previous_session",File(old).name).put("session",File(session).name).put("instance_id",instance.id).put("same_world",true).put("stale_request_rejected",true).toString(2))
            runBlocking{operations.mutate{assertEquals("saved",operations.stopCurrent().getString("state"))}}
            waitFor("game process fully exited",30){repo.sessionStatus()?.opt("process_alive")==false}
            val config=File(repo.projectDir(project.folder),"pyproject.toml")
            val source=config.readText()
            try {
                config.writeText("[invalid toml")
                waitFor("saved session can retry"){vm.state.value.canRestart&&!vm.state.value.busy}
                compose.runOnUiThread{vm.restartCurrentSession()}
                waitFor("build failure returned"){!vm.state.value.restartBusy&&!vm.state.value.busy}
                assertNotNull(vm.state.value.error)
                assertEquals(session,repo.session()!!.getString("session"))
                assertTrue(repo.worldDir(instance.folder).isDirectory)
                compose.runOnUiThread{vm.dismiss()}
            }finally{config.writeText(source)}
            trash=repo.trashWorld(project,instance)
            waitFor("deleted instance disables restart"){!vm.state.value.canRestart&&vm.state.value.restartReason.isNotBlank()}
            compose.runOnUiThread{vm.closeLogs()}
            compose.onNodeWithTag("restart-session").assertIsNotEnabled()
        }finally{
            if(repo.session()?.optString("projectId")==project.id&&repo.active())runBlocking{operations.mutate{operations.stopCurrent()}}
            trash?.let{repo.restore(it)}
        }
    }
    @Test fun pendingLaunchIsCancelledBeforeRestartingTheSameInstance(){
        val operations=ApplicationOperations.get(compose.activity.application);val repo=operations.repository
        check(!repo.active()){ "Do not interrupt an existing user game" }
        val vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
        waitFor("workspace ready"){vm.state.value.workspaceLoaded&&!vm.state.value.busy}
        val project=repo.createProject("QA-PendingRestart-${System.currentTimeMillis()}");val world=repo.defaultWorld(project)
        val plan=runBlocking{operations.mutate{
            val build=operations.core("command",project.folder,JSONObject().put("operation","prepare_run").put("parameters",JSONObject()))
            repo.prepareRun(project,world,File(build.getString("output")))
        }}
        val record=JSONObject(File(plan.session,"launch.json").readText())
        operations.pendingLaunches.create(record)
        operations.pendingLaunches.transition(plan.session.name,"waiting_user","background_launch_unconfirmed","Synthetic OS handoff fixture")
        try {
            waitFor("pending restart enabled"){vm.state.value.phase=="waiting_user"&&vm.state.value.canRestart&&!vm.state.value.busy}
            compose.runOnUiThread{vm.restartCurrentSession()}
            waitFor("pending restart finished",180){!vm.state.value.restartBusy&&!vm.state.value.busy&&repo.session()?.optString("session")!=plan.session.path}
            assertNull(vm.state.value.error)
            val next=repo.session()!!
            assertEquals(world.id,next.getString("instanceId"));assertEquals(plan.session.name,next.getString("previous_session"))
            assertEquals("cancelled",JSONObject(File(plan.session,"status.json").readText()).getString("phase"))
            waitFor("new game initialized",180){repo.sessionStatus()?.optString("phase")=="running"}
        }finally{
            if(repo.session()?.optString("projectId")==project.id&&repo.active())runBlocking{operations.mutate{operations.stopCurrent()}}
        }
    }

}
