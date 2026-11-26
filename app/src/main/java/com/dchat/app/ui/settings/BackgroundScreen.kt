package com.dchat.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.AppState
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.SectionLabel
import com.dchat.app.ui.SettingGroup
import com.dchat.app.ui.SettingRow
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors
import com.dchat.app.ui.theme.defaultWall

@Composable
fun BackgroundScreen(app: AppState, onBack: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val s = app.settings

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val path = app.importImage(it, "wall.img", 1080)
            if (path != null) app.updateSettings { st -> st.copy(bgImage = path) }
        }
    }

    val bmp = remember(s.bgImage) {
        runCatching {
            s.bgImage?.let {
                android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap()
            }
        }.getOrNull()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(L.misc.backgroundTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(230.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(defaultWall))
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = if (s.bgScale == 1) ContentScale.FillBounds else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Color(0x47000000))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(L.misc.backgroundPreview, fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.White)
            }
        }

        SectionLabel(L.misc.sectionZoom)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.bg3)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(L.misc.zoomFill, L.misc.zoomStretch, L.misc.zoomCrop).forEachIndexed { i, label ->
                val on = s.bgScale == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) c.bg else c.bg3)
                        .clickable { app.updateSettings { st -> st.copy(bgScale = i) } }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 12.5.sp,
                        color = if (on) c.text1 else c.text2,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        SectionLabel(L.misc.sectionImage)
        SettingGroup {
            SettingRow(L.misc.pickFromAlbum, onClick = { picker.launch("image/*") }, divider = true)
            SettingRow(L.misc.restoreDefault, value = L.misc.pureColor, onClick = {
                app.updateSettings { st -> st.copy(bgImage = null) }
            })
        }

        Text(
            L.misc.backgroundHint,
            fontSize = 11.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.navigationBarsPadding())
    }
}
