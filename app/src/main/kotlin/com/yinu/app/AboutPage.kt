package com.yinu.app

import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.haooz.chedule.ui.effects.background.BgEffectBackground
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.app.records.RecordsViewModel
import com.yinu.designsystem.YinuTopBar
import com.yinu.designsystem.rememberTopBarMaterialVisible
import com.yinu.designsystem.yinuTopContentPadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference

// Adapted from NexioSchedule AboutScreen, AGPL-3.0-only.
@Composable
internal fun AboutPage(version: String, onBack: () -> Unit, records: RecordsViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val icon = remember(context) { checkNotNull(context.getDrawable(R.mipmap.yinu_launcher)).toBitmap(264, 264).asImageBitmap() }
    val list = rememberLazyListState()
    val spacerPx = with(LocalDensity.current) { 340.dp.toPx() }
    val progress = remember(list, spacerPx) {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / spacerPx).coerceIn(0f, 1f) }
    }
    val background = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    var playing by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { playing = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { playing = true }
    Box(modifier.fillMaxSize().background(colors.background)) {
        BgEffectBackground(
            dynamicBackground = playing,
            isFullSize = true,
            modifier = Modifier.fillMaxSize().layerBackdrop(background),
            alpha = { 1f - progress.value },
        ) {}
        Box(Modifier.fillMaxSize().layerBackdrop(page)) {
            Column(
                Modifier.fillMaxWidth().padding(top = yinuTopContentPadding() + 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    icon, "一隅应用图标",
                    Modifier.size(88.dp).graphicsLayer {
                        val fade = ((progress.value - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1f - fade
                        scaleX = 1f - fade * 0.05f
                        scaleY = scaleX
                    }.clip(ContinuousRoundedRectangle(22.dp)),
                )
                Text(
                    "一隅", color = colors.onBackground, fontSize = 35.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 5.dp).graphicsLayer {
                        alpha = 1f - ((progress.value - 0.20f) / 0.15f).coerceIn(0f, 1f)
                    },
                )
                Text("版本 $version", color = colors.onSurfaceVariant, fontSize = 14.sp,
                    modifier = Modifier.graphicsLayer { alpha = 1f - (progress.value / 0.20f).coerceIn(0f, 1f) })
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                state = list,
                overscrollEffect = null,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                    top = yinuTopContentPadding(), bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(340.dp)) }
                item {
                    AboutCard(background) {
                        BasicComponent(title = "一隅", summary = "想到什么，就发给一隅。\n个人记录与文件收集。")
                        BasicComponent(title = "本地优先", summary = "记录与附件保存在本机，支持导出 ZIP 备份。")
                    }
                }
                item {
                    AboutCard(background) {
                        ArrowPreference(title = "NexioSchedule", summary = "界面与动效 · AGPL-3.0",
                            onClick = { openReference(context, "https://github.com/HaoZai000/NexioSchedule", records) })
                        ArrowPreference(title = "MiuiX", summary = "设置组件与交互",
                            onClick = { openReference(context, "https://github.com/compose-miuix-ui/miuix", records) })
                    }
                }
                item {
                    AboutCard(background) {
                        BasicComponent(title = "数据与备份", summary = "备份文件包含记录原文和附件，请妥善保存。最近删除在本机保留 30 天。")
                    }
                }
                item { SettingsNote("一隅，收纳每一个值得留下的瞬间。") }
            }
        }
        val compact by remember { derivedStateOf { progress.value > 0.5f } }
        val materialVisible = rememberTopBarMaterialVisible(list)
        YinuTopBar(if (compact) "关于一隅" else "", page, onBack, materialVisible = materialVisible)
    }
}

@Composable
private fun AboutCard(backdrop: com.kyant.backdrop.Backdrop, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = remember { ContinuousRoundedRectangle(20.dp) }
    Card(
        Modifier.fillMaxWidth().then(if (isRenderEffectSupported())
            Modifier.drawBackdrop(backdrop, shape = { shape }, effects = { blur(20.dp.toPx()) }, highlight = null)
            else Modifier),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(colors.surface.copy(alpha = 0.78f), colors.onSurface),
        content = content,
    )
}
