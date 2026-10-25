package net.easecation.mcpywrap

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.studio.StudioViewModel
import dev.mcpywrap.workspace.WorkspaceRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class HostRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo=WorkspaceRepository()
    private fun waitFor(seconds:Int,label:String,condition:()->Boolean){
        val end=System.currentTimeMillis()+seconds*1000L
        while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(250)}
        error("Timed out $label: ${repo.sessionStatus()}")
    }
    @Test fun inspectDualRuntimeAndFullSurface(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        repo.initialize()
        val project=repo.projects().last{it.folder.startsWith("QA-NativeMod")}
        var vm:StudioViewModel?=null
        compose.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[StudioViewModel::class.java]}
        waitFor(30,"ready"){!vm!!.state.value.busy&&vm!!.state.value.runtimeReady}
        compose.runOnUiThread{vm!!.run(project)}
        waitFor(30,"launch"){!vm!!.state.value.busy};assertNull(vm!!.state.value.error)
        try{
            waitFor(340,"HUD"){repo.sessionStatus()?.optString("phase")=="running"}
            if(InstrumentationRegistry.getArguments().getString("hold")=="true"){
                val stop=File("/sdcard/mcpy/logs/host-probe-stop");stop.delete()
                waitFor(1800,"external probes finished"){stop.exists()}
            }
            val session=File(repo.session()!!.getString("session"))
            val control=File(app.noBackupFilesDir,"game-control/${session.name}")
            fun request(action:String,code:String="",side:String="client"):JSONObject{
                val id=UUID.randomUUID().toString().replace("-","")
                WorkspaceRepository.atomic(File(control,if(action=="screenshot")"screenshot-request.json"else "request.json"),JSONObject().put("id",id).put("action",action).put("side",side).put("raw",action=="inspect").put("code",code).toString().toByteArray())
                val output=File(control,"$id.json");waitFor(25,action){output.isFile}
                return JSONObject(output.readText())
            }
            File("/sdcard/mcpy/logs/host-inspect.json").writeText(request("inspect","{'debug': '_mcpy_host' in __import__('sys').modules, 'modules':[x for x in __import__('sys').modules if 'Native' in x or 'safaia' in x.lower()]} ").toString(2))
            val result=request("execute","import sys, common.minecraftMod as m\nimport mod.server.extraServerApi as s\n_result={'modules':[x for x in sys.modules if 'Native' in x or 'safaia' in x.lower()], 'server':str(s.GetSystem('NativeProof','ProofServer')), 'level':str(s.GetLevelId()), 'loader':dir(m.MinecraftMod)}")
            File("/sdcard/mcpy/logs/host-runtime-probe.json").writeText(result.toString(2))
            assertTrue(result.toString(),result.getBoolean("ok"))
            val server=request("execute","import mod.server.extraServerApi as s\n_result={'level':str(s.GetLevelId()),'system':str(s.GetSystem('NativeProof','ProofServer'))}","server")
            File("/sdcard/mcpy/logs/host-server-runtime.json").writeText(server.toString(2))
            assertEquals("local-world",server.getJSONObject("reply").getJSONObject("ret").getJSONObject("value").getString("level"))
            assertNotEquals("None",server.getJSONObject("reply").getJSONObject("ret").getJSONObject("value").getString("system"))
            val capture=request("screenshot")
            assertTrue(capture.toString(),capture.getBoolean("ok"))
            assertTrue(File(capture.getString("path")).length()>1000)
            assertTrue(capture.getInt("width")>1000)
            val application=dev.mcpywrap.application.ApplicationOperations.get(app)
            val projectPath="projects/"+project.folder
            val original=linkedMapOf<File,ByteArray>()
            try{
                for((side,name) in listOf("client" to "ProofClient", "server" to "ProofServer")){
                    val source=repo.projectDir(project.folder).walkTopDown().first{it.name=="$name.py"&&!it.path.contains("/build/")}
                    original[source]=source.readBytes()
                    source.appendText("\nHOST_RELOAD_PROBE = u'源码热更验证'\n")
                    val filename=source.relativeTo(repo.projectDir(project.folder)).invariantSeparatorsPath
                    val changed=kotlinx.coroutines.runBlocking {application.mutate {application.execute("runtime.reload",projectPath,
                        JSONObject().put("session",session.name).put("side",side).put("kind","python").put("filename",filename))}}
                    assertTrue(changed.toString(),changed.getBoolean("ok"))
                    val module=changed.getJSONObject("value").getString("module")
                    val probe=kotlinx.coroutines.runBlocking {application.mutate {application.execute("runtime.py",projectPath,
                        JSONObject().put("session",session.name).put("side",side).put("code",
                            "# coding: utf-8\nimport sys\nprint(u'双端日志验证')\n_result = sys.modules['$module'].HOST_RELOAD_PROBE"))}}
                    assertEquals(probe.toString(),"源码热更验证",probe.getString("value"))
                    assertTrue(application.logs(projectPath,session.name,source=side).getString("text").contains("双端日志验证"))
                }
            }finally{for((source,bytes) in original)WorkspaceRepository.atomic(source,bytes)}

        }finally{repo.requestStop();waitFor(90,"saved"){repo.sessionStatus()?.optString("phase")=="saved"}}
    }
}
