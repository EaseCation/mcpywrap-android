package dev.mcpywrap.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionLogScreen(session:String,onBack:()->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current;val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    var snapshot by remember(session){mutableStateOf<LogSnapshot?>(null)}
    var failure by remember(session){mutableStateOf<String?>(null)}
    var paused by rememberSaveable(session){mutableStateOf(false)}
    var refresh by remember{mutableIntStateOf(0)}
    var channel by rememberSaveable{mutableStateOf(LogChannel.ALL)}
    var tone by rememberSaveable{mutableStateOf<LogTone?>(null)}
    var query by rememberSaveable{mutableStateOf("")}
    var onlyMatches by rememberSaveable{mutableStateOf(false)}
    var raw by rememberSaveable{mutableStateOf(false)}
    var follow by rememberSaveable{mutableStateOf(true)}
    var menu by remember{mutableStateOf(false)}
    var current by rememberSaveable{mutableIntStateOf(0)}
    val list=rememberLazyListState();val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(session,paused,refresh,owner){
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
            do {
                try {
                    val next=withContext(Dispatchers.IO){SessionLogReader.read(SessionLogReader.directory(session))}
                    snapshot=next;failure=null
                }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message?:"读取失败"}
                if(paused)break
                delay(1200)
            }while(isActive)
        }
    }
    val base=remember(snapshot,channel,tone){snapshot?.rows.orEmpty().filter{it.inChannel(channel)&&(tone==null||it.tone==tone)}}
    fun text(row:LogRow)=if(raw)row.raw else row.text
    val rows=remember(base,query,onlyMatches,raw){if(onlyMatches&&query.isNotBlank())base.filter{text(it).contains(query,true)}else base}
    val matches=remember(rows,query,raw){if(query.isBlank())emptyList()else rows.indices.filter{text(rows[it]).contains(query,true)}}
    LaunchedEffect(query,channel,tone,onlyMatches,raw){current=0}
    LaunchedEffect(matches.size){current=current.coerceIn(0,maxOf(0,matches.size-1))}
    LaunchedEffect(snapshot,follow,channel,tone,onlyMatches){if(follow&&query.isBlank()&&rows.isNotEmpty())list.scrollToLogEnd(rows.size)}
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging){if(dragging)follow=false}
    LaunchedEffect(query,channel,tone,onlyMatches,raw){if(query.isNotBlank()&&matches.isNotEmpty())list.scrollToItem(matches.first())}
    fun navigate(delta:Int){if(matches.isNotEmpty()){follow=false;current=(current+delta+matches.size)%matches.size;scope.launch{list.scrollToItem(matches[current])}}}
    BackHandler(onBack=onBack)
    val awayFromBottom by remember{derivedStateOf{list.canScrollForward}}
    Scaffold(floatingActionButton={
        if(rows.isNotEmpty()&&awayFromBottom)SmallFloatingActionButton(
            onClick={keyboard?.hide();follow=query.isBlank();scope.launch{list.scrollToLogEnd(rows.size)}},
            modifier=Modifier.testTag("logs-bottom")
        ){Icon(Icons.Outlined.VerticalAlignBottom,"滚动到底部")}
    },topBar={TopAppBar(title={Column{Text("运行日志");Text(File(session).name,style=MaterialTheme.typography.labelSmall,maxLines=1)}},
        navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},
        actions={IconButton(onClick={paused=!paused},modifier=Modifier.testTag("logs-pause")){Icon(if(paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,if(paused)"继续更新"else "暂停更新")}
            IconButton(onClick={
                val full=rows.joinToString("\n"){text(it)}
                // Keep the clipboard Binder payload below Android's transaction limit.
                val copied=full.take(200_000)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("日志",copied))
                scope.launch{snackbar.showSnackbar(if(copied.length<full.length)"已复制前 20 万字符，完整日志保留在文件中"else "已复制当前筛选结果")}
            },enabled=rows.isNotEmpty()){Icon(Icons.Outlined.ContentCopy,"复制当前结果")}
            IconButton(onClick={refresh++}){Icon(Icons.Outlined.Refresh,"重新读取")}})},snackbarHost={SnackbarHost(snackbar)}){padding->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()){
            SecondaryScrollableTabRow(selectedTabIndex=channel.ordinal,edgePadding=12.dp){LogChannel.entries.forEach{item->Tab(selected=channel==item,onClick={channel=item},text={Text(item.label)})}}
            OutlinedTextField(query,{query=it;follow=false;current=0},Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).testTag("logs-search"),
                placeholder={Text("搜索日志")},singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();navigate(0)}),leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"清除搜索")}})
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box{FilterChip(selected=tone!=null,onClick={menu=true},label={Text(tone?.label?:"所有级别")},trailingIcon={Icon(Icons.Outlined.ArrowDropDown,null)})
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        DropdownMenuItem(text={Text("所有级别")},onClick={tone=null;menu=false})
                        LogTone.entries.forEach{item->DropdownMenuItem(text={Text(item.label)},onClick={tone=item;menu=false})}
                    }}
                FilterChip(selected=onlyMatches,onClick={onlyMatches=!onlyMatches},label={Text("仅匹配")})
                FilterChip(selected=raw,onClick={raw=!raw},label={Text("原始记录")})
                FilterChip(selected=follow,onClick={query="";follow=true;scope.launch{if(rows.isNotEmpty())list.scrollToLogEnd(rows.size)}},label={Text("跟随最新")})
            }
            if(query.isNotEmpty())Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(matches.isEmpty())"0 条匹配"else "${current+1} / ${matches.size} 条匹配",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                IconButton(onClick={navigate(-1)},enabled=matches.isNotEmpty()){Icon(Icons.Outlined.KeyboardArrowUp,"上一条匹配")}
                IconButton(onClick={navigate(1)},enabled=matches.isNotEmpty()){Icon(Icons.Outlined.KeyboardArrowDown,"下一条匹配")}
            }
            Text(if(snapshot==null)"正在读取…"else "${if(paused)"已暂停"else "实时更新"} · ${rows.size} 条${if(snapshot?.limited==true)" · 仅最近记录（每源最多 512 KiB）"else ""}",Modifier.padding(horizontal=12.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)
            failure?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
            snapshot?.let{value->if(value.errors.isNotEmpty()||value.unparsed>0)Text((value.errors+if(value.unparsed>0)listOf("${value.unparsed} 条记录格式无法识别，请检查原始日志文件")else emptyList()).joinToString("\n"),Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            HorizontalDivider()
            if(snapshot==null&&failure==null)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            else if(rows.isEmpty())Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Text(when{
                failure!=null->"未能读取，点击右上角重试"
                query.isNotBlank()&&onlyMatches||tone!=null->"当前范围没有匹配记录"
                snapshot?.available?.isEmpty()==true->"此会话尚未生成日志"
                else->"此频道暂无日志"
            })}
            else LazyColumn(state=list,contentPadding=PaddingValues(top=6.dp,bottom=64.dp),modifier=Modifier.weight(1f).testTag("logs-list")){
                itemsIndexed(rows){index,row->
                    val selected=query.isNotBlank()&&matches.getOrNull(current)==index
                    Surface(color=if(selected)MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.35f)else Color.Transparent){
                        val prefix=if(!raw&&channel in listOf(LogChannel.ALL,LogChannel.GAME))"[${row.channelLabel}] "else ""
                        SelectionContainer{Text(logText(text(row),row.tone,query,prefix),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=1.dp),
                            fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=18.sp)}
                    }
                }
            }
        }
    }
}

@Composable
private fun logText(text:String,tone:LogTone,query:String,prefix:String="")=buildAnnotatedString {
    append(prefix);append(text)
    val dark=androidx.compose.foundation.isSystemInDarkTheme()
    val color=when(tone){
        LogTone.ERROR->MaterialTheme.colorScheme.error
        LogTone.WARNING->if(dark)Color(0xFFFFCB6B)else Color(0xFF805500)
        LogTone.SUCCESS->if(dark)Color(0xFFA5D6A7)else Color(0xFF24652A)
        LogTone.DEBUG->MaterialTheme.colorScheme.onSurfaceVariant
        LogTone.TAG->MaterialTheme.colorScheme.tertiary
        LogTone.NORMAL->MaterialTheme.colorScheme.onSurface
    }
    addStyle(SpanStyle(color=MaterialTheme.colorScheme.onSurfaceVariant),0,prefix.length)
    addStyle(SpanStyle(color=color),prefix.length,length)
    for(match in logMatches(text,query))addStyle(SpanStyle(background=MaterialTheme.colorScheme.tertiaryContainer,color=MaterialTheme.colorScheme.onTertiaryContainer),prefix.length+match.first,prefix.length+match.last+1)
}

private suspend fun LazyListState.scrollToLogEnd(count:Int) {
    if(count==0)return
    scrollToItem(count-1)
    // A traceback can be taller than the viewport; bring its end into view as well.
    layoutInfo.visibleItemsInfo.firstOrNull{it.index==count-1}?.let{scrollBy(it.size.toFloat()+layoutInfo.afterContentPadding)}
}
