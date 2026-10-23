package dev.mcpywrap.workspace

import dev.mcpywrap.model.WelcomeTemplate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceRepositoryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun repo()=WorkspaceRepository(temp.newFolder()).apply{initialize()}
    private fun zip(vararg entries:Pair<String,String>):ByteArrayInputStream {
        val bytes=ByteArrayOutputStream();ZipOutputStream(bytes).use{z->entries.forEach{(path,text)->z.putNextEntry(ZipEntry(path));z.write(text.toByteArray());z.closeEntry()}};return ByteArrayInputStream(bytes.toByteArray())
    }
    private fun reject(block:()->Unit){assertThrows(Exception::class.java,block)}
    @Test fun singlePackImportBecomesStandardProjectWithoutMovingGitMetadata(){
        val r=repo();val original=r.createProject("原组件")
        val stage=r.staging()
        File(r.projectDir(original.folder),"behavior_pack").copyRecursively(stage,overwrite=true)
        File(stage,".git/HEAD").apply{parentFile.mkdirs();writeText("ref: refs/heads/main\n")}
        val imported=r.publishImport(stage,"导入的单包","https://example.invalid/repo.git")
        val base=r.projectDir(imported.folder)
        assertTrue(File(base,"behavior_pack/manifest.json").isFile)
        assertTrue(File(base,"behavior_pack/WelcomeDemo/client.py").isFile)
        assertTrue(File(base,".git/HEAD").isFile)
        assertFalse(File(base,"behavior_pack/.git").exists())
    }
    @Test fun templateIsOrdinarySourceWithFreshIdentity(){
        val r=repo();val a=r.createProject("欢迎语 A");val b=r.createProject("欢迎语 B")
        assertEquals(WelcomeTemplate.files[WelcomeTemplate.CLIENT_PATH],r.open(a.folder,WelcomeTemplate.CLIENT_PATH).text)
        assertTrue(File(r.projectDir(a.folder),"behavior_pack/WelcomeDemo/__init__.py").isFile)
        assertNotEquals(r.packs(a.folder).single().uuid,r.packs(b.folder).single().uuid)
    }
    @Test fun filesAndMetadataSurviveNewRepository(){val r=repo();val p=r.createProject("手机作品");val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);r.save(p.folder,f,f.text+"\n# 真实保存\n");val reopened=WorkspaceRepository(r.root);assertEquals(p,reopened.projects().single());assertTrue(reopened.open(p.folder,f.path).text.endsWith("# 真实保存\n"))}
    @Test fun staleEditorCannotOverwriteExternalChanges(){val r=repo();val p=r.createProject("测试");val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);val disk=File(r.projectDir(p.folder),f.path);disk.writeText("# external");assertThrows(FileConflict::class.java){r.save(p.folder,f,"# mine")};assertEquals("# external",disk.readText())}
    @Test fun deletedOpenFileIsNotSilentlyRecreated(){val r=repo();val p=r.createProject("测试");val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);File(r.projectDir(p.folder),f.path).delete();assertThrows(FileConflict::class.java){r.save(p.folder,f,"x")}}
    @Test fun traversalAndAbsolutePathsRejected(){val r=repo();listOf("../outside","/tmp/outside","a/../../outside","a\\b").forEach{path->reject{WorkspaceRepository.inside(r.root,path)}}}
    @Test fun symlinksRejected(){val r=repo();val p=r.createProject("测试");Files.createSymbolicLink(File(r.projectDir(p.folder),"outside").toPath(),temp.root.toPath());reject{r.list(p.folder)}}
    @Test fun zipSlipCannotEscapeStaging(){val r=repo();reject{r.importZip(zip("../escape.py" to "evil"),"bad")};assertFalse(File(r.root,"build/escape.py").exists());assertTrue(r.projects().isEmpty())}
    @Test fun nonZipLeavesNoProject(){val r=repo();reject{r.importZip(ByteArrayInputStream("hello".toByteArray()),"bad")};assertTrue(r.projects().isEmpty())}
    @Test fun zipImportAndExportPreserveBytes(){val r=repo();val p=r.importZip(zip("addon/__init__.py" to "","addon/client.py" to "# coding: utf-8\n文字 = '你好'\n"),"导入");val export=r.export(p.folder);val p2=export.inputStream().use{r.importZip(it,"再次导入")};assertEquals(r.open(p.folder,"addon/client.py").text,r.open(p2.folder,"addon/client.py").text)}
    @Test fun duplicateNameDoesNotOverwrite(){val r=repo();r.createProject("原作");reject{r.importZip(zip("x.py" to "new"),"原作")};assertTrue(File(r.projectDir("原作"),WelcomeTemplate.CLIENT_PATH).isFile)}
    @Test fun fileManagerMoveTrashRestore(){val r=repo();val p=r.createProject("测试");r.createEntry(p.folder,"","scripts",true);r.createEntry(p.folder,"scripts","test.py",false);r.move(p.folder,"scripts/test.py","scripts/moved.py");val id=r.trash(p.folder,"scripts/moved.py");assertFalse(File(r.projectDir(p.folder),"scripts/moved.py").exists());r.restore(id);assertTrue(File(r.projectDir(p.folder),"scripts/moved.py").isFile)}
    @Test fun restoreDoesNotOverwriteNewFile(){val r=repo();val p=r.createProject("测试");val path=WelcomeTemplate.CLIENT_PATH;val id=r.trash(p.folder,path);File(r.projectDir(p.folder),path).writeText("new");reject{r.restore(id)};assertEquals("new",File(r.projectDir(p.folder),path).readText())}
    @Test fun binaryIsNotOpenedAsText(){val r=repo();val p=r.createProject("测试");File(r.projectDir(p.folder),"a.png").writeBytes(byteArrayOf(0,1,2));reject{r.open(p.folder,"a.png")}}
    @Test fun sourceDraftCannotChangeRunningSnapshot(){val r=repo();val p=r.createProject("测试");val w=r.defaultWorld(p);val plan=r.prepareRun(p,w);val deployed=File(plan.data,"games/com.netease/behavior_packs").walk().first{it.name=="client.py"};val old=deployed.readText();val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);r.save(p.folder,f,"# changed");assertEquals(old,deployed.readText());assertTrue(plan.data.canonicalPath.startsWith(File(r.root,"worlds").canonicalPath));reject{r.prepareRun(p,w)}}
    @Test fun nextVersionAppliesOnlyToSelectedWorld(){val r=repo();val p=r.createProject("测试");val a=r.defaultWorld(p);val b=r.createWorld("另一个世界",p);val first=r.prepareRun(p,a);File(first.session,"status.json").writeText("{\"phase\":\"saved\"}");val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);r.save(p.folder,f,"# B");val second=r.prepareRun(p,b);val old=File(first.data,"games/com.netease/behavior_packs").walk().first{it.name=="client.py"};val newer=File(second.data,"games/com.netease/behavior_packs").walk().first{it.name=="client.py"};assertNotEquals(old.readText(),newer.readText());assertEquals("# B",newer.readText())}
    @Test fun modifiedPublishedVersionIsNeverReusedForNewRun(){val r=repo();val p=r.createProject("测试");val w=r.defaultWorld(p);val a=r.prepareRun(p,w);File(a.session,"status.json").writeText("{\"phase\":\"saved\"}");val version=File(r.root,"mods").walk().first{it.name=="client.py"};version.writeText("corrupt");val b=r.prepareRun(p,w);assertNotEquals("corrupt",File(b.data,"games/com.netease/behavior_packs").walk().first{it.name=="client.py"}.readText())}
    @Test fun buildRequiredProjectIsPreservedButCannotRun(){val r=repo();val p=r.importZip(zip("pyproject.toml" to "[project]\nname='needs-build'"),"构建项目");reject{r.prepareRun(p,r.defaultWorld(p))};assertTrue(File(r.projectDir(p.folder),"pyproject.toml").isFile)}
    @Test fun missingProcessEvidenceIsUnknown(){val r=repo();val p=r.createProject("测试");val plan=r.prepareRun(p,r.defaultWorld(p));File(plan.session,"status.json").writeText(JSONObject().put("phase","running").put("heartbeat",0).toString());assertEquals("unknown",r.sessionStatus()!!.getString("phase"));assertFalse(r.active())}
    @Test fun draftsSurviveProcessRecreationWithoutReplacingSource(){val r=repo();val p=r.createProject("草稿");val f=r.open(p.folder,WelcomeTemplate.CLIENT_PATH);r.storeDraft(p.folder,f,"# unsaved");val reopened=WorkspaceRepository(r.root);assertEquals("# unsaved",reopened.readDraft(p.folder,f.path)!!.second);assertEquals(f.text,reopened.open(p.folder,f.path).text);reopened.discardDraft(p.folder,f.path);assertNull(reopened.readDraft(p.folder,f.path))}
    @Test fun renamedProjectKeepsItsWorldAssociation(){val r=repo();val p=r.createProject("旧名称");val w=r.defaultWorld(p);val renamed=r.renameProject(p,"新名称");assertEquals(p.id,renamed.id);assertEquals("新名称",r.worlds(renamed).single().project);assertTrue(r.worldDir(r.worlds(renamed).single().folder).exists())}
    @Test fun trashWholeProjectMovesInstancesAndCanRestore(){val r=repo();val p=r.createProject("作品");val w=r.defaultWorld(p);val id=r.trashProject(p);assertTrue(r.projects().isEmpty());assertFalse(r.worldDir(w.folder).exists());assertEquals(id,r.trashItems().single().id);r.restore(id);assertEquals(p,r.projects().single())}
    @Test fun emptyTemplateHasAValidBehaviorPack(){val r=repo();val p=r.createProject("空白","empty");assertEquals("behavior_packs",r.packs(p.folder).single().kind);assertTrue(File(r.projectDir(p.folder),"behavior_pack/Main/__init__.py").isFile)}

    @Test fun worldBackupExcludesOfficialRuntimeAssets(){val r=repo();val p=r.createProject("备份作品");val w=r.createWorld("备份",p);val base=r.worldDir(w.folder);for(path in listOf("minecraftWorlds/local-world/level.dat","games/com.netease/behavior_packs/test/modMain.py","games/com.netease/vanilla.mcp")){val f=File(base,path);f.parentFile.mkdirs();f.writeText("fixture")};java.util.zip.ZipFile(r.backup(p,w)).use{zip->assertNotNull(zip.getEntry("minecraftWorlds/local-world/level.dat"));assertNotNull(zip.getEntry("games/com.netease/behavior_packs/test/modMain.py"));assertNull(zip.getEntry("games/com.netease/vanilla.mcp"))}}

    private fun legacy(r:WorkspaceRepository,title:String,owner:String=""):WorkWorld {
        val dir=File(r.root,"worlds/$title").apply{mkdirs()};val id=java.util.UUID.randomUUID().toString()
        File(dir,"world.json").writeText(JSONObject().put("id",id).put("name",title).put("project",owner).toString())
        File(dir,"minecraftWorlds/local-world").mkdirs();File(dir,"minecraftWorlds/local-world/level.dat").writeText("saved-world-$id")
        return WorkWorld(title,title,owner,id=id,projectId="",createdAt=dir.lastModified())
    }
    @Test fun instancesRequireAnExistingProject(){val r=repo();reject{r.createWorld("孤立",WorkProject("不存在","不存在",java.util.UUID.randomUUID().toString()))};assertTrue(File(r.root,"worlds").listFiles().isNullOrEmpty())}
    @Test fun sameInstanceNameCanExistUnderDifferentProjects(){val r=repo();val a=r.createProject("A");val b=r.createProject("B");val x=r.createWorld("开发测试",a);val y=r.createWorld("开发测试",b);assertEquals("A/开发测试",x.folder);assertEquals("B/开发测试",y.folder);assertEquals(listOf(x),r.worlds(a));assertEquals(listOf(y),r.worlds(b));assertNotEquals(x.id,y.id)}
    @Test fun otherProjectCannotRunBackupOrDeleteAnInstance(){val r=repo();val a=r.createProject("A");val b=r.createProject("B");val w=r.defaultWorld(a);val metadata=File(r.worldDir(w.folder),"world.json").readText();reject{r.prepareRun(b,w)};reject{r.backup(b,w)};reject{r.trashWorld(b,w)};assertEquals(metadata,File(r.worldDir(w.folder),"world.json").readText());assertFalse(File(r.root,"logs/active.json").exists())}
    @Test fun deletingInstancePreservesSourceAndRestoresItsSave(){val r=repo();val p=r.createProject("作品");val w=r.defaultWorld(p);val save=File(r.worldDir(w.folder),"minecraftWorlds/local-world/level.dat");save.parentFile.mkdirs();save.writeText("world progress");val source=r.open(p.folder,WelcomeTemplate.CLIENT_PATH).text;val id=r.trashWorld(p,w);assertTrue(r.worlds(p).isEmpty());assertEquals(source,r.open(p.folder,WelcomeTemplate.CLIENT_PATH).text);r.restore(id);val restored=r.worlds(p).single();assertEquals(w.id,restored.id);assertEquals("world progress",File(r.worldDir(restored.folder),"minecraftWorlds/local-world/level.dat").readText())}
    @Test fun activeInstanceCannotBeDeletedButAnIdleSiblingCan(){val r=repo();val p=r.createProject("作品");val active=r.defaultWorld(p);val idle=r.createWorld("另一个",p);r.prepareRun(p,active);reject{r.trashWorld(p,active)};r.trashWorld(p,idle);assertEquals(listOf(active.id),r.worlds(p).map{it.id});reject{r.trashProject(p)}}
    @Test fun deletingDefaultSelectsAnotherInstanceInSameProject(){val r=repo();val a=r.createProject("A");val b=r.createProject("B");val first=r.defaultWorld(a);val second=r.createWorld("第二个",a);r.defaultWorld(b);r.trashWorld(a,first);assertEquals(second.id,r.defaultWorld(a).id);r.trashWorld(a,second);assertEquals(a.id,r.defaultWorld(a).projectId)}
    @Test fun legacyOwnedWorldMigratesWithoutChangingSave(){val r=repo();val p=r.createProject("作品");val old=legacy(r,"旧世界",p.folder);val saved=File(r.worldDir(old.folder),"minecraftWorlds/local-world/level.dat").readText();val current=r.worlds(p).single();assertEquals("作品/旧世界",current.folder);assertEquals(p.id,current.projectId);assertEquals(old.id,current.id);assertEquals(saved,File(r.worldDir(current.folder),"minecraftWorlds/local-world/level.dat").readText());assertFalse(r.worldDir(old.folder).exists());assertEquals(listOf(current),r.worlds(p))}
    @Test fun unownedLegacyWorldRequiresExplicitAdoption(){val r=repo();val a=r.createProject("A");val b=r.createProject("B");val old=legacy(r,"独立旧世界");assertTrue(r.worlds(a).isEmpty());assertTrue(r.worlds(b).isEmpty());assertTrue(r.worldDir(old.folder).exists());val migrated=r.adoptLegacyWorld(a,r.legacyWorlds().single());assertEquals(a.id,migrated.projectId);assertTrue(r.legacyWorlds().isEmpty());reject{r.adoptLegacyWorld(b,migrated)};reject{r.prepareRun(b,migrated)}}
    @Test fun oldWorldMatchingProjectFolderCanMigrate(){val r=repo();val p=r.createProject("同名");val old=legacy(r,"同名",p.folder);val migrated=r.worlds(p).single();assertEquals("同名/同名",migrated.folder);assertEquals(old.id,migrated.id);assertTrue(File(r.worldDir(migrated.folder),"minecraftWorlds/local-world/level.dat").exists())}
    @Test fun legacyMigrationDoesNotOverwriteExistingInstance(){val r=repo();val p=r.createProject("作品");val fresh=r.createWorld("同名",p);val old=legacy(r,"同名",p.folder);val all=r.worlds(p);assertEquals(2,all.size);assertEquals(fresh.folder,all.first{it.id==fresh.id}.folder);assertNotEquals(fresh.folder,all.first{it.id==old.id}.folder)}
    @Test fun migrationDefersMovingALiveLegacyWorld(){val r=repo();val p=r.createProject("作品");val old=legacy(r,"活动旧世界",p.folder);val session=File(r.root,"logs/session-old").apply{mkdirs()};File(session,"status.json").writeText(JSONObject().put("phase","running").put("heartbeat",System.currentTimeMillis()).toString());File(r.root,"logs/active.json").writeText(JSONObject().put("data",r.worldDir(old.folder).path).put("session",session.path).toString());assertEquals(old.folder,r.worlds(p).single().folder);reject{r.trashWorld(p,old)};File(session,"status.json").writeText("{\"phase\":\"saved\"}");assertEquals("作品/活动旧世界",r.worlds(p).single().folder)}
    @Test fun instanceRestoreFollowsRenamedOwnerAndRejectsNameReuse(){val r=repo();val p=r.createProject("作品");val w=r.defaultWorld(p);val id=r.trashWorld(p,w);val renamed=r.renameProject(p,"新名称");r.restore(id);assertEquals("新名称/开发测试",r.worlds(renamed).single().folder);val again=r.trashWorld(renamed,r.worlds(renamed).single());val projectTrash=r.trashProject(renamed);r.createProject("新名称");reject{r.restore(again)};assertTrue(File(r.root,"trash/$projectTrash/content").exists())}
    @Test fun projectTrashAndRestoreIncludeAllInstanceData(){val r=repo();val p=r.createProject("作品");val w=r.defaultWorld(p);val save=File(r.worldDir(w.folder),"minecraftWorlds/local-world/level.dat");save.parentFile.mkdirs();save.writeText("progress");val id=r.trashProject(p);assertFalse(r.projectDir(p.folder).exists());assertFalse(r.worldDir(w.folder).exists());assertTrue(r.legacyWorlds().isEmpty());r.restore(id);assertEquals(w.id,r.worlds(p).single().id);assertEquals("progress",save.readText())}

    @Test fun legacyRestoreCannotHideAnInstanceInsideAnotherWorld(){val r=repo();val p=r.createProject("作品");val old=legacy(r,"旧实例",p.folder);val obstacle=legacy(r,"作品");val id=r.trashWorld(p,r.worlds(p).single());reject{r.restore(id)};assertTrue(File(r.root,"trash/$id/content/minecraftWorlds/local-world/level.dat").exists());r.adoptLegacyWorld(p,r.legacyWorlds().first{it.id==obstacle.id});r.restore(id);val restored=r.worlds(p).first{it.id==old.id};assertEquals(p.id,restored.projectId);assertEquals("作品/旧实例",restored.folder)}

}
