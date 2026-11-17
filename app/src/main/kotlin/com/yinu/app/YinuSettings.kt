package com.yinu.app

import top.yukonga.miuix.kmp.utils.overScrollVertical

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.yinu.app.records.RecordsViewModel
import com.yinu.app.ai.AiSettings
import com.yinu.app.sync.CloudSyncSettings
import com.yinu.designsystem.YinuTopBar
import com.yinu.designsystem.rememberTopBarMaterialVisible
import com.yinu.designsystem.yinuColorScheme
import com.yinu.designsystem.yinuTopContentPadding
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixContentColor

private enum class SettingsDestination(val title: String) {
    Appearance("外观"),
    Storage("数据与存储"),
    About("关于一隅"),
    Shortcuts("桌面快捷记录"),
    Reminders("记录提醒"),
    Ai("AI 服务"),
    Cloud("跨设备同步"),
}

@Composable
internal fun YinuSettings(
    records: RecordsViewModel,
    appearance: AppearanceOptions,
    darkTheme: Boolean,
    onThemeChange: (AppearanceMode) -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onMonetChange: (Boolean) -> Unit,
    onMenuLabelsChange: (Boolean) -> Unit,
    onLocateRecord: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val progress = remember { Animatable(if (destination == null) 0f else 1f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val brand = androidx.compose.material3.MaterialTheme.colorScheme
    val colors = remember(darkTheme, brand) {
        val defaults = if (darkTheme) darkColorScheme() else lightColorScheme()
        defaults.copy(
            primary = brand.primary,
            onPrimary = brand.onPrimary,
            primaryVariant = brand.primary,
            background = brand.background,
            onBackground = brand.onBackground,
            onBackgroundVariant = brand.onSurfaceVariant,
            surface = brand.background,
            surfaceVariant = brand.surface,
            surfaceContainer = brand.surface,
            onSurfaceContainer = brand.onSurface,
            onSurface = brand.onSurface,
            onSurfaceVariantSummary = brand.onSurfaceVariant,
            onSurfaceVariantActions = brand.onSurfaceVariant,
        )
    }

    fun open(page: SettingsDestination) {
        if (destination != null) return
        destination = page
        scope.launch { progress.animateTo(1f, PagePushEnter) }
    }

    fun goBack() {
        scope.launch {
            progress.animateTo(0f, PagePushExit)
            destination = null
        }
    }
    BackHandler(enabled = destination != null, onBack = ::goBack)

    MiuixTheme(colors = colors) {
        CompositionLocalProvider(MiuixContentColor provides colors.onBackground) {
            Box(modifier.fillMaxSize().clipToBounds()) {
                SettingsPage(
                    title = "设置",
                    onBack = onBack,
                    modifier = Modifier.pagePushSource { progress.value }
                        .then(if (destination == null) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
                ) { minimumBodyHeight ->
                    item {
                        // Reserve title-collapse travel even when the menu fits on screen.
                        Column(Modifier.fillMaxWidth().heightIn(min = minimumBodyHeight),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsGroup("偏好设置") {
                                ArrowPreference(
                                    title = SettingsDestination.Appearance.title,
                                    summary = "主题和模糊效果",
                                    endActions = { SettingValue(appearance.mode.label) },
                                    onClick = { open(SettingsDestination.Appearance) },
                                )
                                ArrowPreference(title = SettingsDestination.Ai.title,
                                    summary = "配置大模型 API", onClick = { open(SettingsDestination.Ai) })
                            }

                            SettingsGroup("数据管理") {
                                ArrowPreference(title = SettingsDestination.Cloud.title,
                                    summary = "登录后自动合并多台设备的记录", onClick = { open(SettingsDestination.Cloud) })
                                ArrowPreference(
                                    title = SettingsDestination.Storage.title,
                                    summary = "本地用量、备份与恢复",
                                    onClick = { open(SettingsDestination.Storage) },
                                )
                            }

                            SettingsGroup("快捷与提醒") {
                                ArrowPreference(title = "桌面快捷记录", summary = "从桌面快速记录文字和图片", onClick = { open(SettingsDestination.Shortcuts) })
                                ArrowPreference(title = "记录提醒", summary = "查看、修改和取消本地提醒", onClick = { open(SettingsDestination.Reminders) })
                            }

                            SettingsGroup("应用信息") {
                                ArrowPreference(
                                    title = SettingsDestination.About.title,
                                    endActions = { SettingValue(version) },
                                    onClick = { open(SettingsDestination.About) },
                                )
                            }
                        }
                    }
                }
                destination?.let { page ->
                    PagePushScrim { progress.value }
                    val pageModifier = Modifier.pagePushDestination { progress.value }
                    if (page == SettingsDestination.Storage) {
                        StorageSettings(records, onLocateRecord, ::goBack, pageModifier)
                        return@let
                    }
                    if (page == SettingsDestination.About) {
                        AboutPage(version, ::goBack, records, pageModifier)
                        return@let
                    }
                    if (page == SettingsDestination.Shortcuts) {
                        QuickRecordSettings(::goBack, pageModifier)
                        return@let
                    }
                    if (page == SettingsDestination.Reminders) {
                        ReminderSettings(records, onLocateRecord, ::goBack, pageModifier)
                        return@let
                    }
                    if (page == SettingsDestination.Ai) {
                        AiSettings(::goBack, pageModifier)
                        return@let
                    }
                    if (page == SettingsDestination.Cloud) {
                        CloudSyncSettings(::goBack, pageModifier)
                        return@let
                    }
                    SettingsPage(page.title, ::goBack, pageModifier) { minimumBodyHeight ->
                        when (page) {
                            SettingsDestination.Appearance -> item {
                                AppearanceSettings(
                                    appearance, darkTheme, onThemeChange, onBlurChange, onMonetChange, onMenuLabelsChange,
                                    modifier = Modifier.heightIn(min = minimumBodyHeight),
                                )
                            }
                            SettingsDestination.Storage -> Unit
                            SettingsDestination.About -> Unit
                            SettingsDestination.Shortcuts -> Unit
                            SettingsDestination.Reminders -> Unit
                            SettingsDestination.Ai -> Unit
                            SettingsDestination.Cloud -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable (Backdrop) -> Unit = {},
    content: LazyListScope.(minimumBodyHeight: Dp) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val compactTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val materialVisible = rememberTopBarMaterialVisible(listState)
    val background = MiuixTheme.colorScheme.background
    BoxWithConstraints(modifier.fillMaxSize().background(background)) {
        val topPadding = yinuTopContentPadding() + 8.dp
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
        // Grouped short pages reserve the body viewport so the title can collapse.
        // This is a minimum only: longer bodies must remain fully scrollable.
        val minimumBodyHeight = (maxHeight - topPadding - bottomPadding).coerceAtLeast(0.dp)
        Box(
            Modifier.matchParentSize()
                .then(if (isRenderEffectSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                state = listState,
                overscrollEffect = null,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = topPadding,
                    end = 16.dp,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        title,
                        Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp).semantics { heading() },
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                content(minimumBodyHeight)
            }
        }
        YinuTopBar(if (compactTitle) title else "", backdrop, onBack, materialVisible = materialVisible)
        overlay(backdrop)
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
internal fun SettingValue(text: String) {
    Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantActions, fontSize = 14.sp)
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.onBackgroundVariant, fontSize = 13.sp)
}

internal fun openReference(context: Context, address: String, records: RecordsViewModel) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()))
    } catch (_: ActivityNotFoundException) {
        records.notify("没有可用的浏览器")
    }
}


