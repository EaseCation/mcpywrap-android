package dev.mcpywrap.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.mcpywrap.engineering.EngineeringClient
import dev.mcpywrap.workspace.WorkspaceRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** 真实发布核心与独立 :core 进程验收，只在测试模拟器运行。 */
class EngineeringCoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val client=EngineeringClient(context)
    private val repo=WorkspaceRepository()

    @Test fun cancellingPreviewWaitsForWorkerAndNextRequestStillWorks()=runBlocking {
        repo.initialize()
        val initialVersion=client.request("status").getString("version")
        val options=JSONObject().put("title","QA-CancelLoading-${System.nanoTime()}").put("modName","CancelMod")
            .put("version","1.0.0").put("scriptDir","CancelScripts").put("serverSystem","CancelServer").put("clientSystem","CancelClient")
        repeat(3){
            val started=CompletableDeferred<Unit>()
            val task=launch{client.request("preview_project",options=options,onProgress={started.complete(Unit)})}
            withTimeout(30_000){started.await()};task.cancelAndJoin()
            assertFalse(repo.projectDir(options.getString("title")).exists())
            assertEquals(initialVersion,client.request("status").getString("version"))
        }
    }

    @Test fun sharedCoreResolvesMergesExportsAndReimports()= runBlocking {
        repo.initialize()
        val initialVersion=client.request("status").getString("version")
        val main=repo.createProject("QA-Core-${System.nanoTime()}")
        val dep=repo.createProject("QA-Dependency-${System.nanoTime()}","empty")
        val directory=repo.projectDir(main.folder);val dependency=repo.projectDir(dep.folder)
        File(dependency,"behavior_pack/dependency.py").writeText("DEPENDENCY = True\n")
        File(dependency,"behavior_pack/shared.py").writeText("OWNER = 'dependency'\n")
        File(directory,"behavior_pack/shared.py").writeText("OWNER = 'main'\n")
        fun texture(root:File,key:String){File(root,"behavior_pack/terrain_texture.json").writeText("{\"texture_data\":{\"$key\":{\"textures\":\"$key\"}}}")}
        texture(directory,"main");texture(dependency,"dependency")
        File(directory,"pyproject.toml").writeText("""
            [project]
            name = "core-fixture"
            version = "1.0.0"
            [tool.mcpywrap]
            target_dir = "build"
            local_dependencies = ["../${dep.folder}"]
        """.trimIndent())
        val resolved=client.request("resolve",main.folder)
        assertEquals(1,resolved.getJSONArray("dependencies").length())
        val phases=mutableListOf<String>()
        val built=client.request("build",main.folder,onProgress={phases.add(it)})
        assertTrue(phases.contains("正在启动工程核心"))
        assertTrue(phases.contains("正在解析组件依赖"))
        assertTrue(phases.contains("正在组装行为包和资源包"))
        val output=File(built.getString("output"))
        assertEquals("OWNER = 'main'\n",File(output,"behavior_pack/shared.py").readText())
        assertTrue(File(output,"behavior_pack/dependency.py").isFile)
        assertEquals(2,JSONObject(File(output,"behavior_pack/terrain_texture.json").readText()).getJSONObject("texture_data").length())
        assertEquals(initialVersion,built.getString("coreVersion"))
        val exported=client.request("package",main.folder)
        ZipFile(exported.getString("artifact")).use{zip->assertNotNull(zip.getEntry("core-fixture_bp/dependency.py"));assertNotNull(zip.getEntry("core-fixture_bp/entities/"))}
        val imported=File(exported.getString("artifact")).inputStream().use{repo.importZip(it,"QA-Reimport-${System.nanoTime()}")}
        assertTrue(File(repo.projectDir(imported.folder),"behavior_pack/dependency.py").isFile)
        assertTrue(File(client.request("build",imported.folder).getString("output"),"behavior_pack/dependency.py").isFile)
        val world=repo.defaultWorld(main)
        val plan=repo.prepareRun(main,world,output)
        assertTrue(File(plan.data,"games/com.netease/behavior_packs").walk().any{it.name=="dependency.py"})
        WorkspaceRepository.atomic(File(plan.session,"status.json"),"{\"phase\":\"saved\"}".toByteArray())
        val evidence=File("/sdcard/mcpy/logs/product-evidence/core-integration.json")
        evidence.parentFile.mkdirs();evidence.writeText(JSONObject().put("coreVersion",built.getString("coreVersion")).put("dependencyMerged",true).put("sourceExported",true).put("reimported",true).put("deployed",true).toString(2))
    }

    @Test fun unsupportedScriptDoesNotRunOrCreateInstance()=runBlocking {
        repo.initialize();val project=repo.createProject("QA-Unsupported-${System.nanoTime()}")
        val root=repo.projectDir(project.folder)
        val sentinel=File(root,"should-not-exist")
        File(root,"generate.py").writeText("open('should-not-exist','w').write('bad')")
        File(root,"pyproject.toml").writeText("""
            [project]
            name = "unsupported"
            version = "1.0.0"
            [tool.mcpywrap]
            target_dir = "build"
            [tool.mcpywrap.scripts.generate]
            python = "generate.py"
            [tool.mcpywrap.hooks]
            before_build = ["generate"]
        """.trimIndent())
        val result=runCatching{client.request("build",project.folder)}
        assertTrue(result.isFailure);assertTrue(result.exceptionOrNull()!!.message!!.contains("构建脚本"))
        assertFalse(sentinel.exists());assertFalse(File(root,"build").exists());assertTrue(repo.worlds(project).isEmpty())
    }

    @Test fun releaseCanBeInstalledVerifiedAndRolledBackWithoutRestartingEditor()=runBlocking {
        repo.initialize()
        val before=client.request("status")
        val update=client.request("update",version="0.6.2")
        assertTrue(update.getBoolean("verified"))
        assertEquals("969e892118017e1a6b2b9ec8384522ff4573b217364ff8e4b1a73c3006942012",update.getJSONObject("selection").getString("current"))
        assertEquals("0.6.2",client.request("status").getString("version"))
        val after=client.request("rollback")
        assertEquals(before.getString("version"),after.getString("version"))
        val file=File("/sdcard/mcpy/logs/product-evidence/core-update.json");file.parentFile.mkdirs()
        file.writeText(JSONObject().put("downloadedFromPyPI",true).put("sha256",update.getJSONObject("selection").getString("current")).put("rollbackVersion",after.getString("version")).put("editorPid",android.os.Process.myPid()).toString(2))
    }
}
