package com.dchat.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.tools.UserToolStore
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 弹层里一行用户工具（只用于展示，按名称排序） */
private data class UserToolRowData(
    val id: String,
    val name: String,
    val description: String,
    /** 有 web/index.html = 网页工具；否则按 data 工具展示/打开时提示 */
    val web: Boolean
)

/**
 * 聊天页「⋮ → 工具」弹层：列出全部用户工具，点一行直接打开工具页。
 *
 * - 数据来自 [UserToolStore.list]（读盘放 IO，弹层打开时加载）；
 * - 标签：`web/index.html` 存在 →「网页」，否则「数据」；
 * - 空列表给引导文案；最大高度 70% 屏高，超出滚动；深色/浅色都走主题色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserToolsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenTool: (String) -> Unit
) {
    if (!visible) return
    val ctx = LocalContext.current
    val c = LocalColors.current
    val L = LocalStrings.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    var tools by remember { mutableStateOf<List<UserToolRowData>?>(null) }

    LaunchedEffect(Unit) {
        tools = withContext(Dispatchers.IO) { loadUserTools(ctx) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        contentColor = c.text1,
        scrimColor = Color(0x66000000)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
        ) {
            Text(
                L.chat.userTools,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
            val list = tools
            when {
                list == null -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.tools.uiWorking, fontSize = 12.5.sp, color = c.text3)
                }

                list.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        L.chat.userToolsEmpty,
                        fontSize = 12.5.sp,
                        color = c.text3,
                        lineHeight = 19.sp
                    )
                }

                else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(list, key = { it.id }) { tool ->
                        UserToolRow(
                            data = tool,
                            onClick = { onOpenTool(tool.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun UserToolRow(data: UserToolRowData, onClick: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    data.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(7.dp))
                UserToolTag(web = data.web)
            }
            if (data.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    data.description,
                    fontSize = 11.5.sp,
                    color = c.text3,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun UserToolTag(web: Boolean) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (web) c.brand.copy(alpha = 0.14f) else c.bg3)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            if (web) L.chat.userToolsWeb else L.chat.userToolsData,
            fontSize = 9.5.sp,
            color = if (web) c.brand else c.text3
        )
    }
}

/** 读盘：用户工具 + 是否存在 web/index.html（入口页存在才算网页工具） */
private fun loadUserTools(ctx: Context): List<UserToolRowData> = runCatching {
    val toolsRoot = File(ctx.filesDir, "tools")
    UserToolStore.list(ctx)
        .filter { it.id.startsWith("user.") }
        .map { def ->
            val index = File(File(File(toolsRoot, def.id), "web"), "index.html")
            UserToolRowData(
                id = def.id,
                name = def.name,
                description = def.description,
                web = index.isFile
            )
        }
        .sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())
