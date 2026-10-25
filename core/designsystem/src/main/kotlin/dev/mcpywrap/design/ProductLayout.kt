package dev.mcpywrap.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One centered content grid; compact screens use one column, wide screens cap line length. */
@Composable
fun ProductPage(modifier:Modifier=Modifier,scrollState:ScrollState=rememberScrollState(),content:@Composable ColumnScope.()->Unit){
    BoxWithConstraints(modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
        val gutter=if(maxWidth<McpyLayout.compactBreakpoint)McpySpacing.page else McpyLayout.wideGutter
        Column(Modifier.widthIn(max=McpyLayout.contentWidth).fillMaxWidth().verticalScroll(scrollState)
            .padding(horizontal=gutter,vertical=McpySpacing.lg),verticalArrangement=Arrangement.spacedBy(McpySpacing.lg),content=content)
    }
}

@Composable
fun SectionCard(title:String,icon:ImageVector,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    ProductCard(modifier){
        Column(Modifier.fillMaxWidth().padding(McpySpacing.xl),verticalArrangement=Arrangement.spacedBy(McpySpacing.md)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(McpySpacing.md)){
                Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
                Text(title,style=MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** Two soft layers replace Material's short, hard elevation shadow on product cards. */
@Composable
private fun Modifier.productCardShadow():Modifier {
    val dark=androidx.compose.foundation.isSystemInDarkTheme()
    val shape=McpyShapes.card
    return this
        .dropShadow(shape,androidx.compose.ui.graphics.shadow.Shadow(radius=32.dp,offset=androidx.compose.ui.unit.DpOffset(0.dp,12.dp),color=androidx.compose.ui.graphics.Color.Black.copy(alpha=if(dark)0.12f else 0.03f)))
        .dropShadow(shape,androidx.compose.ui.graphics.shadow.Shadow(radius=8.dp,offset=androidx.compose.ui.unit.DpOffset(0.dp,2.dp),color=androidx.compose.ui.graphics.Color.Black.copy(alpha=if(dark)0.06f else 0.01f)))
}

@Composable
fun ProductCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,enabled:Boolean=true,
    content:@Composable ColumnScope.()->Unit){
    val geometry=modifier.fillMaxWidth().productCardShadow()
    val colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation=CardDefaults.cardElevation(defaultElevation=0.dp,pressedElevation=0.dp,focusedElevation=0.dp,hoveredElevation=0.dp,draggedElevation=0.dp,disabledElevation=0.dp)
    if(onClick==null)Card(geometry,shape=McpyShapes.card,colors=colors,elevation=elevation,content=content)
    else Card(onClick,geometry,enabled=enabled,shape=McpyShapes.card,colors=colors,elevation=elevation,content=content)
}

@Composable
fun ProductOutlinedCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    OutlinedCard(modifier.fillMaxWidth().productCardShadow(),shape=McpyShapes.card,
        colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),content=content)
}

@Composable
fun ProductFooter(content:@Composable ColumnScope.()->Unit){
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),contentAlignment=Alignment.TopCenter){
        val gutter=if(maxWidth<McpyLayout.compactBreakpoint)McpySpacing.page else McpyLayout.wideGutter
        Column(Modifier.widthIn(max=McpyLayout.contentWidth).fillMaxWidth().padding(horizontal=gutter,vertical=McpySpacing.lg),content=content)
    }
}
