package dev.mcpywrap.workspace

import dev.mcpywrap.model.WelcomeTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorkProject(val folder: String, val name: String, val id: String, val source: String = "")
data class WorkWorld(
    val folder: String, val name: String, val project: String, val revision: String = "",
    val id: String, val projectId: String, val createdAt: Long
)
data class WorkFile(val path: String, val name: String, val directory: Boolean, val bytes: Long)
data class OpenFile(val path: String, val text: String, val digest: String)
data class Pack(val path: String, val uuid: String, val kind: String, val name: String)
data class TrashItem(val id: String, val label: String)
data class RunPlan(val data: File, val session: File, val world: String)
class FileConflict : IllegalStateException("文件已被其他编辑器修改。请重新打开后合并，当前草稿仍保留。")

/** Public disk is authoritative. Call from an IO dispatcher, never the UI thread. */
class WorkspaceRepository(val root: File = File("/sdcard/mcpy")) {
    companion object {
        const val MAX_TEXT = 2L * 1024 * 1024
        const val MAX_IMPORT = 512L * 1024 * 1024
        const val MAX_ENTRIES = 20000
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if(n < 0) break; md.update(buffer, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun atomic(file: File, bytes: ByteArray) {
            file.parentFile!!.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
            try {
                java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        fun name(value: String): String {
            val text = value.trim()
            require(text.isNotBlank() && text.length <= 80 && text !in listOf(".", "..") && !text.startsWith(".") && text.none { it == '/' || it == '\\' || it.isISOControl() || it in ":*?\"<>|" }) { "名称不能包含路径符号，最多 80 个字符" }
            return text
        }
        fun inside(base: File, relative: String, allowRoot: Boolean = false): File {
            require(!File(relative).isAbsolute && '\\' !in relative && relative.split('/').none { it == ".." }) { "无效路径" }
            val target = File(base, relative).canonicalFile
            val canonical = base.canonicalFile
            require(target.path.startsWith(canonical.path + File.separator) || (allowRoot && target == canonical)) { "文件超出工作目录" }
            // Refuse symlinks even when they point back inside, including ancestors.
            var walk = File(base, relative).absoluteFile
            while (walk != base.absoluteFile && walk.parentFile != null) { require(!Files.isSymbolicLink(walk.toPath())) { "不支持符号链接" }; walk = walk.parentFile!! }
            return target
        }
    }
    fun initialize() { listOf("projects", "mods", "worlds", "build", "exports", "logs", "trash").forEach { check(File(root, it).isDirectory || File(root, it).mkdirs()) { "无法访问 /sdcard/mcpy，请允许所有文件访问" } } }
    private fun writeJson(file: File, data: JSONObject) = atomic(file, data.toString(2).toByteArray())
    private fun readJson(file: File) = JSONObject(file.readText())
    fun projectDir(folder: String): File { name(folder); return inside(File(root, "projects"), folder) }
    fun worldDir(folder: String): File {
        require(folder.split('/').size in 1..2)
        folder.split('/').forEach { name(it) }
        return inside(File(root, "worlds"), folder)
    }
    private fun currentProject(project: WorkProject): WorkProject = projects().firstOrNull { it.id == project.id }
        ?: error("作品已不存在，请先恢复所属作品")
    private fun instanceGroup(project: WorkProject) = inside(File(root,"worlds"),name(project.folder))
    fun projects(): List<WorkProject> = File(root,"projects").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }.map { dir ->
        val meta = File(dir,".mcpy-project.json")
        val data = if(meta.isFile) readJson(meta) else JSONObject().put("id",UUID.randomUUID().toString()).put("name",dir.name).also { writeJson(meta,it) }
        UUID.fromString(data.getString("id"))
        WorkProject(dir.name,data.getString("name"),data.getString("id"),data.optString("source"))
    }.sortedBy { it.name.lowercase() }
    private fun worldMetadata(): List<File> = File(root,"worlds").listFiles().orEmpty()
        .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        .flatMap { dir ->
            if(File(dir,"world.json").isFile) listOf(File(dir,"world.json"))
            else dir.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && File(it,"world.json").isFile }.map { File(it,"world.json") }
        }
    private fun readWorld(file: File): WorkWorld {
        val m=readJson(file)
        return WorkWorld(file.parentFile!!.canonicalFile.relativeTo(File(root,"worlds").canonicalFile).invariantSeparatorsPath,
            m.getString("name"),m.optString("project"),m.optString("revision"),m.getString("id"),
            m.optString("projectId"),m.optLong("createdAt",file.lastModified()))
    }
    private fun inUse(world: WorkWorld): Boolean {
        if(!active())return false
        val config=session()?:return false
        return config.optString("instanceId")==world.id || config.optString("data").let { it.isNotBlank() && File(it).canonicalFile==worldDir(world.folder).canonicalFile }
    }
    private fun requireIdle(world: WorkWorld) { require(!inUse(world)) { "这个实例正在运行，请先保存退出，再删除或移动" } }

    /** Upgrade only known ownership. Unowned legacy worlds stay intact until explicitly adopted. */
    @Synchronized fun migrateLegacyWorlds() {
        val owners=projects()
        for(file in worldMetadata()) {
            val m=readJson(file)
            val owner=if(m.optString("projectId").isNotBlank()) owners.find { it.id==m.optString("projectId") }
                else owners.find { it.folder==m.optString("project") }
            if(owner==null)continue
            val world=readWorld(file)
            if(inUse(world))continue
            val group=instanceGroup(owner)
            // A flat 0.2 world can have the same name as a project; defer that conflict without overwriting it.
            if(File(group,"world.json").isFile && group.canonicalFile!=file.parentFile!!.canonicalFile)continue
            val alreadyGrouped=file.parentFile!!.parentFile!!.canonicalFile==group.canonicalFile
            if(!alreadyGrouped && group.canonicalFile==file.parentFile!!.canonicalFile){
                relocateSameNamedLegacy(owner,world,m)
                continue
            }
            val destination=if(alreadyGrouped)file.parentFile!! else availableInstancePath(owner,world.name,world.id)
            if(!m.has("projectId")||m.optString("project")!=owner.folder||!m.has("createdAt")||!alreadyGrouped){
                m.put("projectId",owner.id).put("project",owner.folder).put("createdAt",world.createdAt)
                if(!alreadyGrouped)m.put("migratedFrom",world.folder).put("name",destination.name)
                writeJson(file,m)
                if(!alreadyGrouped){destination.parentFile!!.mkdirs();check(file.parentFile!!.renameTo(destination)){"旧版实例迁移失败，原文件已保留"}}
            }
        }
    }
    private fun relocateSameNamedLegacy(owner: WorkProject,world: WorkWorld,metadata: JSONObject): WorkWorld {
        val original=worldDir(world.folder)
        val temporary=inside(File(root,"worlds"),"迁移暂存-"+world.id)
        require(!temporary.exists()){ "已有未完成的迁移，请保留两个目录并检查" }
        metadata.put("projectId",owner.id).put("project",owner.folder).put("createdAt",world.createdAt).put("migratedFrom",world.folder)
        writeJson(File(original,"world.json"),metadata)
        check(original.renameTo(temporary))
        try {
            val destination=availableInstancePath(owner,world.name,world.id)
            destination.parentFile!!.mkdirs()
            writeJson(File(temporary,"world.json"),metadata.put("name",destination.name))
            check(temporary.renameTo(destination))
            return readWorld(File(destination,"world.json"))
        } catch(e:Exception){
            val group=instanceGroup(owner)
            if(group.isDirectory&&group.listFiles().orEmpty().isEmpty())group.delete()
            if(!original.exists())temporary.renameTo(original)
            throw e
        }
    }
    private fun availableInstancePath(owner: WorkProject,title: String,id: String): File {
        val group=instanceGroup(owner)
        require(!File(group,"world.json").exists()) { "旧版世界占用了作品目录，请先在旧版恢复列表中处理" }
        val preferred=inside(group,name(title))
        if(!preferred.exists())return preferred
        val alternative=inside(group,name(title.take(55)+" · 恢复-"+id.take(8)))
        require(!alternative.exists()) { "已有同名实例，原存档没有被覆盖" }
        return alternative
    }
    fun worlds(project: WorkProject): List<WorkWorld> {
        val owner=currentProject(project);migrateLegacyWorlds()
        return worldMetadata().map(::readWorld).filter { it.projectId==owner.id || (it.projectId.isBlank()&&it.project==owner.folder) }
            .sortedWith(compareBy<WorkWorld>{it.createdAt}.thenBy{it.id})
    }
    fun legacyWorlds(): List<WorkWorld> {
        migrateLegacyWorlds();val owners=projects()
        return worldMetadata().map(::readWorld).filter { w ->
            if(w.projectId.isNotBlank())owners.none{it.id==w.projectId}
            else owners.none{it.folder==w.project}
        }.sortedBy{it.name}
    }
    private fun ownedWorld(project: WorkProject,world: WorkWorld): WorkWorld {
        val owner=currentProject(project)
        return worlds(owner).firstOrNull { it.id==world.id }
            ?: error("这个实例不属于当前作品，不能换绑或运行其他作品的实例")
    }
    fun adoptLegacyWorld(project: WorkProject,world: WorkWorld): WorkWorld {
        val owner=currentProject(project)
        val legacy=legacyWorlds().firstOrNull{it.id==world.id}?:error("这个世界已有所属作品")
        // A stable owner ID is never reassigned to a different project, including after deleting its source.
        require(legacy.projectId.isBlank()||legacy.projectId==owner.id){"请先恢复这个世界原来所属的作品"}
        requireIdle(legacy)
        val original=worldDir(legacy.folder)
        if(original.canonicalFile==instanceGroup(owner).canonicalFile)return relocateSameNamedLegacy(owner,legacy,readJson(File(original,"world.json")))
        val destination=availableInstancePath(owner,legacy.name,legacy.id)
        val metadata=readJson(File(original,"world.json")).put("projectId",owner.id).put("project",owner.folder)
            .put("createdAt",legacy.createdAt).put("migratedFrom",legacy.folder).put("name",destination.name)
        writeJson(File(original,"world.json"),metadata);destination.parentFile!!.mkdirs()
        check(original.renameTo(destination)){"迁移失败，原世界仍保留"}
        return readWorld(File(destination,"world.json"))
    }
    fun createProject(title: String, template: String = "welcome"): WorkProject {
        val stage = staging()
        try {
            when(template) {
                "welcome" -> WelcomeTemplate.files.forEach { (path,text) -> val f=inside(stage,path); f.parentFile!!.mkdirs(); f.writeText(text) }
                "empty" -> {
                    val pack=File(stage,"behavior_pack"); pack.mkdirs()
                    File(pack,"manifest.json").writeText(JSONObject().put("format_version",1).put("header",JSONObject().put("name",name(title)).put("description","普通源码组件").put("uuid",UUID.randomUUID()).put("version",JSONArray(listOf(1,0,0)))).put("modules",JSONArray().put(JSONObject().put("type","data").put("uuid",UUID.randomUUID()).put("version",JSONArray(listOf(1,0,0))))).toString(2))
                    File(pack,"Main").mkdirs();File(pack,"Main/__init__.py").writeText("")
                    File(pack,"Main/modMain.py").writeText("# coding: utf-8\nfrom mod.common.mod import Mod\n\n@Mod.Binding(name=\"Main\", version=\"1.0.0\")\nclass Main(object):\n    @Mod.InitClient()\n    def init_client(self):\n        pass\n")
                }
                else -> error("未知模板")
            }
            // Identity changes are only template instantiation, never field-to-code binding.
            val manifest=File(stage,"behavior_pack/manifest.json");val json=readJson(manifest)
            json.getJSONObject("header").put("uuid",UUID.randomUUID().toString()).put("name",name(title))
            val modules=json.getJSONArray("modules");for(i in 0 until modules.length())modules.getJSONObject(i).put("uuid",UUID.randomUUID().toString())
            manifest.writeText(json.toString(2));return publishImport(stage,title,"")
        } finally { stage.deleteRecursively() }
    }
    fun staging(): File { initialize(); return File(root,"build/import-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    fun publishImport(stage: File, title: String, source: String): WorkProject {
        val folder=name(title); val dest=projectDir(folder);require(!dest.exists()) { "已有同名作品，请换一个名称；原作品不会被覆盖" }
        require(!File(root,"worlds/$folder").exists()){ "同名实例目录仍存在，请恢复原作品或换一个作品名称" }
        // 已有工程配置保持原样；普通组件只整理输入布局，不解析依赖或合并资源。
        if(!File(stage,"pyproject.toml").exists())normalizePlainPacks(stage)
        val all=filesRecursively(stage); require(all.isNotEmpty()) { "没有可导入的文件" };require(all.sumOf { it.length() } <= MAX_IMPORT) { "项目超过 512 MB" }
        val id=UUID.randomUUID().toString();writeJson(File(stage,".mcpy-project.json"),JSONObject().put("id",id).put("name",folder).put("source",source))
        check(stage.renameTo(dest)) { "导入失败，无法移动到项目目录" };return WorkProject(folder,folder,id,source)
    }
    private fun normalizePlainPacks(stage:File) {
        if(File(stage,"manifest.json").isFile){
            val manifest=readJson(File(stage,"manifest.json"));val modules=manifest.getJSONArray("modules")
            val kind=if((0 until modules.length()).any{modules.getJSONObject(it).optString("type")=="data"})"behavior_pack" else "resource_pack"
            val entries=stage.listFiles().orEmpty().filter{it.name !in listOf(".git",".mcpy-project.json")};val target=File(stage,kind)
            require(!target.exists()){ "组件根目录中已有 $kind，无法识别目录结构" };check(target.mkdir())
            entries.forEach{check(it.renameTo(File(target,it.name)))}
            return
        }
        val packs=scanPacks(stage)
        require(packs.groupBy{it.kind}.values.all{it.size<=1}){"一个作品只支持一个行为包和一个资源包，请把其他组件作为工程依赖加入"}
        packs.forEach { pack ->
            val from=inside(stage,pack.path,true);val expected=if(pack.kind=="behavior_packs")"behavior_pack" else "resource_pack"
            if(from.parentFile?.canonicalFile==stage.canonicalFile && from.name.startsWith(expected))return@forEach
            val to=File(stage,expected);require(!to.exists()){ "$expected 已存在，无法整理组件目录" }
            check(from.renameTo(to)){"无法整理组件目录"}
        }
    }
    fun importZip(input: InputStream, title: String): WorkProject {
        val stage=staging()
        try { unpack(input,stage); return publishImport(stage,title,"") } finally { stage.deleteRecursively() }
    }
    fun unpack(input: InputStream, stage: File, depth: Int = 0, budget: ImportBudget = ImportBudget()) {
        require(depth <= 2) { "嵌套压缩包过深" }
        ZipInputStream(input).use { zip ->
            var entry=zip.nextEntry
            while(entry!=null) {
                require(++budget.count <= MAX_ENTRIES) { "压缩包文件过多" }
                val e=entry;val f=inside(stage,e.name)
                require(!f.exists() || (e.isDirectory && f.isDirectory)) { "压缩包有重复路径：${e.name}" }
                if(e.isDirectory) f.mkdirs() else {
                    f.parentFile!!.mkdirs()
                    f.outputStream().use { out -> val buffer=ByteArray(65536);while(true){val n=zip.read(buffer);if(n<0)break;budget.bytes+=n;require(budget.bytes<=MAX_IMPORT){"解压内容超过 512 MB"};out.write(buffer,0,n)} }
                    if(f.extension.lowercase()=="mcpack") {
                        val nested=File(f.parentFile,f.nameWithoutExtension+"-pack");check(!nested.exists());nested.mkdirs();f.inputStream().use{unpack(it,nested,depth+1,budget)};f.delete()
                    }
                }
                zip.closeEntry();entry=zip.nextEntry
            }
        }
        require(budget.count>0) { "不是有效的 ZIP / mcpack / mcaddon 文件" }
    }
    data class ImportBudget(var count: Int = 0, var bytes: Long = 0)
    fun list(folder: String, path: String = ""): List<WorkFile> {
        val base=projectDir(folder);val dir=inside(base,path,true);require(dir.isDirectory)
        return dir.listFiles().orEmpty().filter { it.name !in listOf(".git",".mcpy-project.json") }.map { file ->
            inside(base,file.relativeTo(base).invariantSeparatorsPath)
            WorkFile(file.relativeTo(base).invariantSeparatorsPath,file.name,file.isDirectory,file.length())
        }.sortedWith(compareBy<WorkFile> { !it.directory }.thenBy { it.name.lowercase() })
    }
    fun open(folder: String, path: String): OpenFile {
        val file=inside(projectDir(folder),path);require(file.isFile && file.length()<=MAX_TEXT) { "只支持编辑 2 MB 以内的文本文件；图片等资源可用外部应用打开" }
        val bytes=file.readBytes();require(0.toByte() !in bytes) { "此文件是二进制资源，请用外部应用打开" }
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return OpenFile(path,text,hash(bytes))
    }
    @Synchronized fun save(folder: String, original: OpenFile, content: String): OpenFile {
        val file=inside(projectDir(folder),original.path)
        if(!file.isFile || digest(file)!=original.digest)throw FileConflict()
        val bytes=content.toByteArray();require(bytes.size<=MAX_TEXT);atomic(file,bytes);return OpenFile(original.path,content,hash(bytes))
    }
    private fun draftPath(folder: String,path: String): File = File(root,"build/drafts/"+hash((folder+"/"+path).toByteArray())+".json")
    fun storeDraft(folder: String,original: OpenFile,content: String) {
        inside(projectDir(folder),original.path)
        writeJson(draftPath(folder,original.path),JSONObject().put("path",original.path).put("original",original.text).put("digest",original.digest).put("draft",content))
    }
    fun readDraft(folder: String,path: String): Pair<OpenFile,String>? {
        val f=draftPath(folder,path);if(!f.isFile)return null
        val j=readJson(f);return OpenFile(path,j.getString("original"),j.getString("digest")) to j.getString("draft")
    }
    fun discardDraft(folder: String,path: String) { draftPath(folder,path).delete() }
    fun createEntry(folder: String, directory: String, title: String, isDirectory: Boolean) {
        val file=inside(projectDir(folder),listOf(directory,name(title)).filter { it.isNotEmpty() }.joinToString("/"));require(!file.exists()) { "已存在同名文件" }
        if(isDirectory)check(file.mkdir()) else check(file.createNewFile())
    }
    fun move(folder: String, path: String, newPath: String) {
        require(newPath.isNotBlank() && newPath.split('/').all { runCatching { name(it) }.isSuccess }) { "请填写项目内的目标路径" }
        val base=projectDir(folder);val from=inside(base,path);val to=inside(base,newPath)
        require(from.exists()&&!to.exists()&&to.parentFile!!.isDirectory) { "目标已存在或目标文件夹不存在" };require(!to.path.startsWith(from.path+"/"));check(from.renameTo(to))
    }
    fun trash(folder: String, path: String): String {
        val base=projectDir(folder);val from=inside(base,path);require(from.exists());val id=UUID.randomUUID().toString();val dest=File(root,"trash/$id");dest.mkdirs()
        writeJson(File(dest,"origin.json"),JSONObject().put("project",folder).put("path",path));check(from.renameTo(File(dest,"content")));return id
    }
    fun restore(id: String) {
        val dir=inside(File(root,"trash"),id);val m=readJson(File(dir,"origin.json"))
        when(m.optString("kind")) {
            "project" -> {
                require(!active()){ "请先保存退出游戏" }
                val to=projectDir(m.getString("project"));val source=File(dir,"content")
                val idInTrash=m.optString("projectId").ifBlank { readJson(File(if(source.exists())source else to,".mcpy-project.json")).getString("id") }
                require(!to.exists() || (!source.exists() && readJson(File(to,".mcpy-project.json")).getString("id")==idInTrash)){"原位置已有作品，不能覆盖"}
                val group=inside(File(root,"worlds"),to.name);val instances=File(dir,"instances")
                require(!instances.exists()||!group.exists()){ "实例目录已被占用，不能覆盖" }
                if(source.exists())check(source.renameTo(to))
                if(instances.exists())check(instances.renameTo(group))
            }
            "instance" -> {
                val owner=projects().find{it.id==m.getString("projectId")}?:error("请先恢复所属作品，再恢复实例")
                val content=File(dir,"content");val world=readJson(File(content,"world.json"))
                migrateLegacyWorlds();requireOwnedGroup(owner)
                require(!File(instanceGroup(owner),"world.json").exists()){ "请先恢复占用此作品目录的旧版世界" }
                val to=inside(instanceGroup(owner),name(world.getString("name")))
                require(!to.exists()){ "已有同名实例，不能覆盖" };to.parentFile!!.mkdirs()
                writeJson(File(content,"world.json"),world.put("schemaVersion",2).put("project",owner.folder).put("projectId",owner.id).put("createdAt",world.optLong("createdAt",File(content,"world.json").lastModified())))
                check(content.renameTo(to))
            }
            else -> {
                val to=inside(projectDir(m.getString("project")),m.getString("path"))
                require(!to.exists()){ "原位置已有文件，不能覆盖" };to.parentFile!!.mkdirs();check(File(dir,"content").renameTo(to))
            }
        }
        dir.deleteRecursively()
    }
    fun trashItems(): List<TrashItem> = File(root,"trash").listFiles().orEmpty().filter{File(it,"origin.json").isFile}.sortedByDescending{it.lastModified()}.mapNotNull{dir->runCatching{
        val m=readJson(File(dir,"origin.json"));val label=when(m.optString("kind")){
            "project"->m.getString("project")+" · 作品和测试实例"
            "instance"->m.getString("project")+" / "+m.getString("name")+" · 测试实例"
            else->m.getString("project")+"/"+m.getString("path")
        };TrashItem(dir.name,label)
    }.getOrNull()}
    fun trashWorld(project: WorkProject,world: WorkWorld): String {
        val owner=currentProject(project);val actual=ownedWorld(owner,world);requireIdle(actual)
        val id=UUID.randomUUID().toString();val destination=File(root,"trash/$id").apply{mkdirs()}
        writeJson(File(destination,"origin.json"),JSONObject().put("kind","instance").put("project",owner.folder)
            .put("projectId",owner.id).put("name",actual.name).put("instanceId",actual.id))
        check(worldDir(actual.folder).renameTo(File(destination,"content")))
        instanceGroup(owner).let{if(it.listFiles().orEmpty().isEmpty())it.delete()}
        return id
    }
    private fun requireOwnedGroup(owner: WorkProject) {
        val group=instanceGroup(owner)
        for(file in group.listFiles().orEmpty().map{File(it,"world.json")}.filter{it.isFile}){
            val m=readJson(file)
            require(m.optString("projectId")==owner.id || (m.optString("projectId").isBlank()&&m.optString("project")==owner.folder)) { "此目录含其他作品的实例，请先处理归属，不能整体移动" }
        }
    }
    fun trashProject(project: WorkProject): String {
        val owner=currentProject(project);val instances=worlds(owner);instances.forEach(::requireIdle);requireOwnedGroup(owner)
        require(instances.all { worldDir(it.folder).parentFile!!.canonicalFile==instanceGroup(owner).canonicalFile }) { "请先完成旧版实例迁移" }
        val id=UUID.randomUUID().toString();val dest=File(root,"trash/$id").apply{mkdirs()}
        writeJson(File(dest,"origin.json"),JSONObject().put("kind","project").put("project",owner.folder).put("projectId",owner.id))
        val source=projectDir(owner.folder);val group=instanceGroup(owner)
        check(source.renameTo(File(dest,"content")))
        try { if(group.exists())check(group.renameTo(File(dest,"instances"))) }
        catch(e:Exception){check(File(dest,"content").renameTo(source));dest.deleteRecursively();throw e}
        return id
    }
    fun renameProject(project: WorkProject,title: String): WorkProject {
        val owner=currentProject(project);val instances=worlds(owner);instances.forEach(::requireIdle);requireOwnedGroup(owner)
        require(instances.all { worldDir(it.folder).parentFile!!.canonicalFile==instanceGroup(owner).canonicalFile }) { "请先完成旧版实例迁移" }
        val folder=name(title);val from=projectDir(owner.folder);val to=projectDir(folder)
        val fromWorlds=instanceGroup(owner);val toWorlds=inside(File(root,"worlds"),folder)
        require(!to.exists()&&!toWorlds.exists()){ "已有同名作品或实例目录" };check(from.renameTo(to))
        try { if(fromWorlds.exists())check(fromWorlds.renameTo(toWorlds)) }
        catch(e:Exception){check(to.renameTo(from));throw e}
        writeJson(File(to,".mcpy-project.json"),readJson(File(to,".mcpy-project.json")).put("name",folder))
        for(file in toWorlds.listFiles().orEmpty().map{File(it,"world.json")}.filter{it.isFile})writeJson(file,readJson(file).put("project",folder))
        return owner.copy(folder=folder,name=folder)
    }
    fun packs(folder: String): List<Pack> = scanPacks(projectDir(folder))
    private fun scanPacks(base: File): List<Pack> = filesRecursively(base).filter { it.name=="manifest.json" }.map { f ->
        val m=readJson(f);val header=m.getJSONObject("header");val uuid=header.getString("uuid");UUID.fromString(uuid)
        val modules=m.getJSONArray("modules");val types=(0 until modules.length()).map{modules.getJSONObject(it).getString("type")}
        require(types.any { it=="data" || it=="resources" }) { "${f.parentFile!!.name} 不是支持的中国版组件" }
        Pack(f.parentFile!!.relativeTo(base).invariantSeparatorsPath,uuid,if("data" in types)"behavior_packs" else "resource_packs",header.optString("name",f.parentFile!!.name))
    }.also { require(it.map { p -> p.uuid }.distinct().size==it.size) { "项目内存在重复的组件 UUID，请删除重复包" } }
    fun filesRecursively(base: File): List<File> {
        var count=0
        return base.walkTopDown().onEnter { d -> require(++count<=MAX_ENTRIES){"目录过大"};require(!Files.isSymbolicLink(d.toPath())){"项目包含符号链接"};d.name !in listOf(".git",".gradle","__pycache__") }.filter { f -> require(!Files.isSymbolicLink(f.toPath())){"项目包含符号链接"};f.isFile && f.name!=".mcpy-project.json" }.toList()
    }
    private fun copyTree(from: File, to: File) { filesRecursively(from).forEach { f -> val dest=inside(to,f.relativeTo(from).invariantSeparatorsPath);dest.parentFile!!.mkdirs();f.copyTo(dest) } }
    fun export(folder: String): File {
        val base=projectDir(folder);val target=File(root,"exports/${name(folder)}-${System.currentTimeMillis()}.zip")
        ZipOutputStream(target.outputStream()).use { zip ->filesRecursively(base).forEach { f -> zip.putNextEntry(ZipEntry(f.relativeTo(base).invariantSeparatorsPath));f.inputStream().use{it.copyTo(zip)};zip.closeEntry() } };return target
    }
    fun createWorld(title: String, project: WorkProject): WorkWorld {
        val owner=currentProject(project);val existing=worlds(owner);requireOwnedGroup(owner);val titleName=name(title)
        val group=instanceGroup(owner);require(!File(group,"world.json").exists()){ "请先恢复同名旧版世界" }
        val dir=inside(group,titleName);require(!dir.exists()){ "这个作品已有同名实例" };check(dir.mkdirs())
        val createdAt=maxOf(System.currentTimeMillis(),(existing.maxOfOrNull{it.createdAt}?:0)+1)
        val id=UUID.randomUUID().toString()
        writeJson(File(dir,"world.json"),JSONObject().put("schemaVersion",2).put("name",titleName).put("project",owner.folder)
            .put("projectId",owner.id).put("id",id).put("createdAt",createdAt))
        return readWorld(File(dir,"world.json"))
    }
    fun defaultWorld(project: WorkProject): WorkWorld = worlds(project).firstOrNull() ?: createWorld("开发测试",project)
    fun prepareRun(project: WorkProject, world: WorkWorld, assembled: File? = null): RunPlan {
        val owner=currentProject(project)
        val instance=ownedWorld(owner,world)
        require(!active()) { "请先保存退出当前游戏，再应用修改或切换世界" }
        val source=assembled?.canonicalFile ?: projectDir(owner.folder)
        require(source.isDirectory && source.canonicalPath.startsWith(root.canonicalPath+"/")){"构建目录不在工作区内"}
        val snapshot=File(root,"build/snapshot-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            copyTree(source,snapshot)
            val packs=scanPacks(snapshot);require(packs.isNotEmpty()){ "未找到 manifest.json。此项目可能需要先在构建工具中生成行为包/资源包，再导入产物。" }
            val treeDigest=MessageDigest.getInstance("SHA-256")
            filesRecursively(snapshot).sortedBy{it.relativeTo(snapshot).path}.forEach{treeDigest.update(it.relativeTo(snapshot).invariantSeparatorsPath.toByteArray());treeDigest.update(0.toByte());treeDigest.update(digest(it).toByteArray())}
            val revision=treeDigest.digest().joinToString(""){"%02x".format(it)}
            val version=File(root,"mods/${owner.id}/$revision")
            if(!version.exists()){version.parentFile!!.mkdirs();check(snapshot.renameTo(version))}
            // Always deploy the captured bytes. A previously published public version may have been edited externally.
            val frozen=if(snapshot.exists())snapshot else version
            val data=worldDir(instance.folder);val installed=File(data,"games/com.netease").apply{mkdirs()}
            val sourceRoots=JSONArray();val behavior=JSONArray();val resource=JSONArray()
            listOf("behavior_packs","resource_packs").forEach { kind ->
                val stage=File(installed,"$kind.stage");stage.deleteRecursively();stage.mkdirs()
                packs.filter{it.kind==kind}.forEach{p->val packName="pack_"+p.uuid.replace("-","");val target=File(stage,packName);target.mkdirs();copyTree(inside(frozen,p.path,true),target);if(kind=="behavior_packs"){File(target,"entities").mkdirs();behavior.put(packName);sourceRoots.put(File(installed,"$kind/$packName").path)}else resource.put(packName)}
                val target=File(installed,kind);val old=File(installed,"$kind.previous");old.deleteRecursively();if(target.exists())check(target.renameTo(old));check(stage.renameTo(target));old.deleteRecursively()
            }
            val session=File(root,"logs/session-${UUID.randomUUID()}").apply{mkdirs()}
            val config=JSONObject().put("worldId","local-world").put("worldName",instance.name).put("behavior",behavior).put("resource",resource).put("sourceRoots",sourceRoots).put("revision",revision).put("project",owner.folder).put("projectId",owner.id).put("instanceId",instance.id).put("data",data.path).put("session",session.path)
            writeJson(File(session,"status.json"),JSONObject().put("phase","loading").put("heartbeat",System.currentTimeMillis()))
            writeJson(File(session,"launch.json"),config);writeJson(File(root,"logs/active.json"),config)
            val metadata=readJson(File(data,"world.json")).put("revision",revision);writeJson(File(data,"world.json"),metadata)
            return RunPlan(data,session,instance.name)
        } finally { snapshot.deleteRecursively() }
    }
    fun session(): JSONObject? { val f=File(root,"logs/active.json");return if(f.isFile)readJson(f)else null }
    fun sessionStatus(): JSONObject? {
        val config=session()?:return null;val dir=File(config.getString("session"));require(dir.canonicalPath.startsWith(File(root,"logs").canonicalPath+"/"))
        val status=File(dir,"status.json");if(!status.isFile)return null
        return readJson(status).also { if(it.optString("phase") in listOf("loading","running","saving") && System.currentTimeMillis()-it.optLong("heartbeat")>15000)it.put("phase","interrupted") }
    }
    fun active(): Boolean {
        val status=sessionStatus()?:return false
        if(status.optString("phase")=="saved")return false
        val pid=status.optInt("pid")
        if(pid>0) return try {android.system.Os.kill(pid,0);true} catch(e:Exception){false}
        return status.optString("phase") in listOf("loading","running","saving")
    }
    fun requestStop() { val config=session()?:return; val dir=File(config.getString("session"));require(dir.canonicalPath.startsWith(File(root,"logs").canonicalPath+"/"));atomic(File(dir,"save-exit.request"),byteArrayOf(1)) }
    fun backup(project: WorkProject,world: WorkWorld): File {
        val instance=ownedWorld(project,world)
        requireIdle(instance)
        val base=worldDir(instance.folder);val target=File(root,"exports/${instance.project}-${instance.name}-${System.currentTimeMillis()}.world.zip")
        // Save data and user packs only. Never export the copied official runtime assets.
        val prefixes=listOf("minecraftWorlds/","games/com.netease/behavior_packs/","games/com.netease/resource_packs/")
        ZipOutputStream(target.outputStream()).use{zip->filesRecursively(base).forEach{f->
            val path=f.relativeTo(base).invariantSeparatorsPath
            if(path=="world.json"||prefixes.any{path.startsWith(it)}){
                zip.putNextEntry(ZipEntry(path));f.inputStream().use{it.copyTo(zip)};zip.closeEntry()
            }
        }};return target
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
