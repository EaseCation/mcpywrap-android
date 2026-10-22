package dev.mcpywrap.editor

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import org.eclipse.tm4e.core.registry.IThemeSource

private object Languages {
    private var initialized=false
    @Synchronized fun init(context: Context) {
        if(initialized)return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        for(name in listOf("light","dark")) {
            val path="textmate/$name.json"
            ThemeRegistry.getInstance().loadTheme(ThemeModel(IThemeSource.fromInputStream(context.assets.open(path),path,null),name).apply{isDark=name=="dark"})
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");initialized=true
    }
}

/** Real editor text only. Highlighting never evaluates or rewrites the Mod. */
@Composable
fun SourceEditor(path: String, initialText: String, onChange: (String)->Unit, modifier: Modifier = Modifier) {
    val context=LocalContext.current
    var prepared by remember{mutableStateOf(false)}
    var loadError by remember{mutableStateOf<String?>(null)}
    var attempt by remember{mutableStateOf(0)}
    LaunchedEffect(attempt){
        loadError=null
        try { withContext(Dispatchers.IO){Languages.init(context)};prepared=true }
        catch(error:kotlinx.coroutines.CancellationException){throw error}
        catch(error:Exception){loadError=error.message?:"编辑器初始化失败"}
    }
    if(!prepared){
        Column(modifier.fillMaxWidth().testTag("editor-loading"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
            if(loadError==null){CircularProgressIndicator();Spacer(Modifier.height(16.dp));Text("正在准备源码编辑器")}
            else {Text(loadError!!);OutlinedButton(onClick={attempt++}){Text("重新加载编辑器")}}
        }
        return
    }
    val change by rememberUpdatedState(onChange)
    val dark=isSystemInDarkTheme()
    var editor by remember{mutableStateOf<CodeEditor?>(null)}
    var search by remember{mutableStateOf(false)}
    var needle by remember{mutableStateOf("")};var replacement by remember{mutableStateOf("")}
    var wrap by remember{mutableStateOf(false)}
    Column(modifier) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={editor?.undo()}) {Text("撤销")}
            TextButton(onClick={editor?.redo()}) {Text("重做")}
            TextButton(onClick={search=!search;if(!search)editor?.searcher?.stopSearch()}) {Text("查找")}
            TextButton(onClick={wrap=!wrap;editor?.isWordwrap=wrap}) {Text(if(wrap)"横向滚动" else "自动换行")}
        }
        if(search) {
            Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(needle,{needle=it;if(it.isNotEmpty())editor?.searcher?.search(it,EditorSearcher.SearchOptions(false,false))else editor?.searcher?.stopSearch()},Modifier.weight(1f),label={Text("查找")},singleLine=true)
                TextButton(onClick={editor?.searcher?.gotoNext()},enabled=needle.isNotEmpty()) {Text("下一处")}
            }
            Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(replacement,{replacement=it},Modifier.weight(1f),label={Text("替换为")},singleLine=true)
                TextButton(onClick={editor?.searcher?.replaceThis(replacement)},enabled=needle.isNotEmpty()) {Text("替换")}
            }
        }
        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth().testTag("source-editor"),factory={context->
            ThemeRegistry.getInstance().setTheme(if(dark)"dark" else "light")
            CodeEditor(context).apply {
                setTextSize(14f);typefaceText=android.graphics.Typeface.MONOSPACE;typefaceLineNumber=android.graphics.Typeface.MONOSPACE;tabWidth=4;isLineNumberEnabled=true
                colorScheme=TextMateColorScheme.create(ThemeRegistry.getInstance())
                when(path.substringAfterLast('.').lowercase()) {
                    "py" -> setEditorLanguage(TextMateLanguage.create("source.python",false))
                    "json" -> setEditorLanguage(TextMateLanguage.create("source.json",false))
                }
                setText(initialText);contentDescription="源码编辑器"
                subscribeEvent(ContentChangeEvent::class.java){_,_->change(text.toString())}
                editor=this
            }
        },onRelease={it.release()})
        Text("${path.substringAfterLast('.').uppercase()} · UTF-8",Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
