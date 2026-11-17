package com.yinu.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kyant.capsule.ContinuousRoundedRectangle
import com.yinu.designsystem.yinuColorScheme
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")
private val AppearanceKey = stringPreferencesKey("theme")
private val LightBlurKey = booleanPreferencesKey("lightweight_blur")
private val MonetKey = booleanPreferencesKey("monet")
private val MenuLabelsKey = booleanPreferencesKey("message_menu_labels")

internal enum class AppearanceMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

internal data class AppearanceOptions(val mode: AppearanceMode, val lightweightBlur: Boolean, val monet: Boolean = false,
    val menuLabels: Boolean = false)

internal class AppearancePreferences(context: Context) {
    private val store = context.applicationContext.appearanceDataStore
    val options = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences ->
        AppearanceOptions(
            mode = AppearanceMode.entries.firstOrNull { it.name == preferences[AppearanceKey] } ?: AppearanceMode.System,
            lightweightBlur = preferences[LightBlurKey] ?: false,
            monet = preferences[MonetKey] ?: false,
            menuLabels = preferences[MenuLabelsKey] ?: false,
        )
    }

    suspend fun setMode(mode: AppearanceMode) {
        store.edit { it[AppearanceKey] = mode.name }
    }

    suspend fun setLightweightBlur(enabled: Boolean) {
        store.edit { it[LightBlurKey] = enabled }
    }
    suspend fun setMonet(enabled: Boolean) {
        store.edit { it[MonetKey] = enabled }
    }
    suspend fun setMenuLabels(enabled: Boolean) { store.edit { it[MenuLabelsKey] = enabled } }
}

@Composable
internal fun AppearanceSettings(
    options: AppearanceOptions,
    darkTheme: Boolean,
    onThemeChange: (AppearanceMode) -> Unit,
    onBlurChange: (Boolean) -> Unit,
    onMonetChange: (Boolean) -> Unit,
    onMenuLabelsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the default blue/white switch treatment across light and dark modes.
    // Wallpaper-derived colors apply only when the user enables Monet.
    val switchColors = SwitchDefaults.switchColors(
        checkedThumbColor = if (options.monet) MiuixTheme.colorScheme.onPrimary else Color.White,
        uncheckedThumbColor = Color.White,
        checkedTrackColor = if (options.monet) MiuixTheme.colorScheme.primary else yinuColorScheme(false).primary,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.selectableGroup().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice(AppearanceMode.Light, !darkTheme, Modifier.weight(1f)) { onThemeChange(AppearanceMode.Light) }
            ThemeChoice(AppearanceMode.Dark, darkTheme, Modifier.weight(1f)) { onThemeChange(AppearanceMode.Dark) }
        }
        SettingsGroup("主题") {
            SwitchPreference(
                title = "莫奈取色",
                summary = if (Build.VERSION.SDK_INT >= 31) "从系统壁纸提取主题颜色" else "需要 Android 12 或更高版本",
                checked = options.monet,
                enabled = Build.VERSION.SDK_INT >= 31,
                onCheckedChange = onMonetChange,
                switchColors = switchColors,
            )
            SwitchPreference(
                title = "跟随系统",
                summary = "随手机的浅色或深色模式切换",
                checked = options.mode == AppearanceMode.System,
                switchColors = switchColors,
                onCheckedChange = { follow ->
                    onThemeChange(if (follow) AppearanceMode.System else if (darkTheme) AppearanceMode.Dark else AppearanceMode.Light)
                },
            )
        }
        val advancedBlurAvailable = Build.VERSION.SDK_INT >= 33
        SettingsGroup("视觉效果") {
            SwitchPreference(
                title = "显示菜单功能名称",
                summary = "长按消息时，在图标下显示功能名称",
                checked = options.menuLabels,
                onCheckedChange = onMenuLabelsChange,
                switchColors = switchColors,
            )
            SwitchPreference(
                title = "轻量模糊",
                summary = if (advancedBlurAvailable) "降低顶栏和底栏模糊的渲染开销"
                    else "当前系统已使用兼容的模糊效果",
                checked = options.lightweightBlur,
                enabled = advancedBlurAvailable,
                onCheckedChange = onBlurChange,
                switchColors = switchColors,
            )
        }
    }
}

@Composable
private fun ThemeChoice(mode: AppearanceMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val colors = MiuixTheme.colorScheme
    val preview = yinuColorScheme(mode == AppearanceMode.Dark)
    val shape = ContinuousRoundedRectangle(18.dp)
    Column(
        modifier.clip(shape).selectable(selected = selected, role = Role.RadioButton) {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.42f).clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline, shape),
        ) {
            fun pill(color: Color, x: Float, y: Float, width: Float, height: Float) {
                val corner = size.height * height / 2
                drawRoundRect(
                    color, Offset(size.width * x, size.height * y), Size(size.width * width, size.height * height),
                    CornerRadius(corner),
                )
            }
            drawRect(preview.background)
            pill(preview.onBackground.copy(alpha = 0.65f), 0.42f, 0.17f, 0.16f, 0.035f)
            pill(preview.primaryContainer, 0.43f, 0.31f, 0.43f, 0.13f)
            pill(preview.surface, 0.28f, 0.48f, 0.58f, 0.13f)
            pill(preview.primaryContainer, 0.59f, 0.65f, 0.27f, 0.11f)
            pill(preview.surface, 0.24f, 0.83f, 0.41f, 0.1f)
            pill(preview.surface, 0.69f, 0.83f, 0.085f, 0.1f)
        }
        Text(mode.label, color = if (selected) colors.primary else colors.onSurface, fontSize = 14.sp)
    }
}
