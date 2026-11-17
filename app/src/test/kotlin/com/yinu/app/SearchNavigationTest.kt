package com.yinu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Magnifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import com.yinu.designsystem.yinuColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yinu.app.records.RecordsState
import com.yinu.app.records.RecordsViewModel
import com.yinu.database.MessageEntity
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Use Android layout and touch handling without Robolectric's host AGSL compiler.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SearchNavigationTest {
    // MiuiX's notice host also contains a LazyColumn; target the page beneath it.
    private val pageList = hasScrollToIndexAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        !hasAnyAncestor(hasTestTag("app-notices"))

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cloudLoginShowsValidationNextToTheButton() {
        openSettings()
        compose.onNodeWithText("跨设备同步").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("登录并同步").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        val error = compose.onNodeWithText("请填写 Supabase 的项目地址或 Data API 地址。")
        error.assertIsDisplayed()
    }

    @Test
    fun appearanceSwitchesSystemBarIconsAndPersistsAcrossRecreation() {
        val darkOption = compose.onNodeWithText("深色")
        val lightOption = compose.onNodeWithText("浅色")
        openSettings()
        compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
        darkOption.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { !usesDarkSystemBarIcons() }
        darkOption.assertIsSelected()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { darkOption.isDisplayed() && !usesDarkSystemBarIcons() }
        darkOption.assertIsSelected()
        lightOption.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { usesDarkSystemBarIcons() }
        lightOption.assertIsSelected()
    }

    @Test
    fun favoritesOpensItsOwnPageAndReturnsToSearch() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        for (message in listOf("测试收藏记录", "测试普通记录")) {
            compose.runOnIdle { model.updateDraft(message); model.send() }
            compose.waitUntil(5_000) { compose.onNodeWithText(message).isDisplayed() }
        }
        val rows = (model.timeline.value as RecordsState.Loaded).records
        compose.runOnIdle {
            model.toggleFavorite(rows.first { it.content == "测试收藏记录" }.id)
            model.updateQuery("测试")
        }
        openSearch()
        compose.onNodeWithText("只看收藏").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) { compose.onNodeWithText("测试收藏记录").isDisplayed() }
        compose.onNodeWithText("收藏").assertIsDisplayed()
        compose.onNodeWithText("测试普通记录").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("只看收藏").assertIsDisplayed()
        compose.onNodeWithText("测试普通记录").assertIsDisplayed()
    }

    @Test
    fun searchCategoryReturnMaterialFollowsActualScrollOffset() {
        val source = File(compose.activity.cacheDir, "material.txt").apply { writeText("scroll test") }
        try {
            compose.runOnIdle {
                val authority = "yinu.material.test"
                val provider = PreviewFileSource(source, "text/plain")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
                val uris = (1..12).map { Uri.parse("content://$authority/$it") }
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(uris)
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
            openSearch()
            compose.onNodeWithText("文档").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(800)
            val hidden = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已隐藏")
            val shown = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已显示")
            compose.onNode(hidden).assertExists()
            val list = compose.onNode(pageList)
            list.performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeBy(500)
            compose.onNode(shown).assertExists()
            list.performScrollToIndex(0)
            compose.mainClock.advanceTimeBy(500)
            compose.onNode(hidden).assertExists()
        } finally {
            source.delete()
        }
    }

    @Test
    fun settingsHeadingUsesTheSelectedThemeForeground() {
        openSettings()
        compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        for (dark in listOf(true, false, true)) {
            compose.onNodeWithText(if (dark) "深色" else "浅色")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { usesDarkSystemBarIcons() != dark }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val actual = layouts.single().layoutInput.style.color
            val options = runBlocking { AppearancePreferences(compose.activity).options.first() }
            val scheme = if (options.monet) {
                if (dark) dynamicDarkColorScheme(compose.activity) else dynamicLightColorScheme(compose.activity)
            } else yinuColorScheme(dark)
            val expected = scheme.onBackground
            check(actual == expected) { "Heading color $actual should be $expected for dark=$dark" }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp")
    fun settingsHomeShortContentCanScrollPastMaterialThreshold() {
        openSettings()
        val hidden = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已隐藏")
        val shown = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已显示")
        compose.onNode(hidden).assertExists()
        val list = compose.onNode(pageList)
        list.performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(shown).assertExists()
        // Release and settle: the circle must stay visible after the rubber band returns.
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(shown).assertExists()
        list.performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hidden).assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp")
    fun appearanceShortPageCanScrollPastMaterialThresholdAndReturnToTop() {
        openSettings()
        compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        val hidden = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已隐藏")
        val shown = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已显示")
        compose.onNode(hidden).assertExists()
        val list = compose.onNode(pageList)
        list.performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(shown).assertExists()
        compose.onNodeWithText("轻量模糊").assertIsDisplayed()
        list.performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(800)
        compose.onNode(hidden).assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp")
    fun storageShortPageCanScrollPastMaterialThresholdAndReturnToTop() {
        openStorageSettings()
        val hidden = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已隐藏")
        val shown = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已显示")
        compose.onNode(hidden).assertExists()
        val list = compose.onNode(pageList)
        list.performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(shown).assertExists()
        compose.onNodeWithText("最近删除").assertIsDisplayed()
        list.performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(800)
        compose.onNode(hidden).assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp")
    fun emptyReminderSettingsCanStillCollapseItsTitle() {
        openSettings()
        compose.onNode(pageList).performScrollToNode(hasText("记录提醒"))
        compose.onNodeWithText("记录提醒").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        // Query conversion runs on the background dispatcher; wait for its actual result.
        compose.waitUntil(5_000) { compose.onNodeWithText("还没有记录提醒").isDisplayed() }
        compose.onNode(pageList).performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已显示")).assertExists()
        compose.onNode(pageList).performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(800)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "顶栏材质已隐藏")).assertExists()
    }

    @Test
    fun settingsContentBouncesAtTopAndReturnsAfterRelease() {
        openSettings()
        val row = compose.onNodeWithText("外观")
        val back = compose.onNodeWithContentDescription("返回")
        val originalTop = row.fetchSemanticsNode().boundsInRoot.top
        val buttonTop = back.fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNode(pageList)
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(center)
                moveBy(Offset(0f, 60f), delayMillis = 32)
                moveBy(Offset(0f, 70f), delayMillis = 32)
            }
            compose.mainClock.advanceTimeBy(32)
            check(row.fetchSemanticsNode().boundsInRoot.top > originalTop + 2f)
            check(kotlin.math.abs(back.fetchSemanticsNode().boundsInRoot.top - buttonTop) < 1f)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(2_000)
            check(kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - originalTop) < 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun monetStartsDisabledAndCanBeEnabledPersistently() {
        val preferences = AppearancePreferences(compose.activity)
        check(!runBlocking { preferences.options.first().monet })
        openSettings()
        compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        val monet = compose.onNodeWithText("莫奈取色")
        monet.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { monet.isDisplayed() && runBlocking { preferences.options.first().monet } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onNodeWithText("莫奈取色").isDisplayed() }
        check(runBlocking { preferences.options.first().monet })
    }

    @Test
    fun messageMenuLabelsPersistAndCanBeTurnedOffAgain() {
        val preferences = AppearancePreferences(compose.activity)
        check(!runBlocking { preferences.options.first().menuLabels })
        fun openAppearance() {
            openSettings()
            compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(800)
            compose.onNode(pageList).performScrollToNode(hasText("显示菜单功能名称"))
        }
        fun home() {
            repeat(2) {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.mainClock.advanceTimeBy(500)
            }
        }
        openAppearance()
        compose.onNodeWithText("显示菜单功能名称").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onNodeWithText("显示菜单功能名称").isDisplayed() && runBlocking { preferences.options.first().menuLabels } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onNodeWithText("显示菜单功能名称").isDisplayed() }
        check(runBlocking { preferences.options.first().menuLabels })
        home()
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle { model.updateDraft("菜单名称测试"); model.send() }
        val bubble = compose.onNodeWithText("菜单名称测试")
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithText("引用").assertIsDisplayed()
        compose.onNodeWithText("关联记录").assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithText("关联到 · 0").assertIsDisplayed()
        compose.onNodeWithText("完成").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        openAppearance()
        compose.onNodeWithText("显示菜单功能名称").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onNodeWithText("显示菜单功能名称").isDisplayed() && !runBlocking { preferences.options.first().menuLabels } }
        home()
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithText("关联记录").assertDoesNotExist()
        compose.onNodeWithContentDescription("关联记录", useUnmergedTree = true).assertExists()
    }

    @Test
    fun lightweightBlurSwitchSavesPreference() {
        openSettings()
        compose.onNodeWithText("外观").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        val option = compose.onNodeWithText("轻量模糊")
        compose.onNode(pageList)
            .performScrollToNode(hasText("轻量模糊"))
        compose.mainClock.advanceTimeBy(800)
        option.assertIsDisplayed().performTouchInput { click() }
        val appearance = AppearancePreferences(compose.activity)
        compose.waitUntil(5_000) { runBlocking { appearance.options.first().lightweightBlur } }
    }

    @Test
    fun backupPickerChecksArchiveBeforeWritingRecords() {
        val archive = File(compose.activity.cacheDir, "preview-restore.zip")
        val json = """{"format":"yinu-local-backup","version":1,"records":[{"id":"preview-row","type":"TEXT","text":"检查后恢复的记录","created":1,"updated":1,"favorite":false,"pinned":false,"files":[]}]}"""
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("backup.complete"))
            zip.closeEntry()
        }
        try {
            val authority = "yinu.backup.preview.test"
            val uri = Uri.parse("content://$authority/${archive.name}")
            val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
            compose.runOnIdle {
                val provider = PreviewFileSource(archive, "application/zip")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
            }
            openStorageSettings()
            fun pickArchive() {
                compose.onNodeWithText("从备份恢复").performSemanticsAction(SemanticsActions.OnClick)
                compose.runOnIdle {
                    val activity = shadowOf(compose.activity)
                    val launched = checkNotNull(activity.nextStartedActivityForResult)
                    activity.receiveResult(launched.intent, android.app.Activity.RESULT_OK, Intent().setData(uri))
                }
                compose.waitUntil(5_000) { compose.onNodeWithText("备份完整性检查通过", substring = true).isDisplayed() }
                compose.mainClock.advanceTimeBy(500)
            }
            pickArchive()
            check((model.timeline.value as RecordsState.Loaded).records.isEmpty())
            compose.onNodeWithText("取消").performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(500)
            check((model.timeline.value as RecordsState.Loaded).records.isEmpty())
            pickArchive()
            compose.onNodeWithText("确认恢复").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onNodeWithText("新增 1 条记录，补回 0 个附件").isDisplayed() }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun settingsOpensStorageAndAboutAndBackReturnsToSettings() {
        openSettings()
        compose.onNodeWithText("数据与存储").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("本地用量").assertIsDisplayed()
        compose.onNodeWithText("导出备份").assertIsEnabled()
        compose.onNodeWithText("从备份恢复").assertIsEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        // The about background intentionally keeps scheduling frames while visible.
        compose.onNode(pageList).performScrollToNode(hasText("关于一隅"))
        check(compose.onNodeWithText("关于一隅").fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithText("记录提醒").fetchSemanticsNode().boundsInRoot.top)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("关于一隅").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        val version = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0).versionName
        compose.onNodeWithText("版本 $version").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("NexioSchedule").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
    }

    private fun openSettings() {
        val settings = compose.onNodeWithContentDescription("设置")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(800)
    }

    private fun openStorageSettings() {
        openSettings()
        compose.onNodeWithText("数据与存储").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
    }

    @Test
    fun undoAndRecentlyDeletedBothRestoreARecord() {
        val message = "可以找回的记录"
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle {
            model.updateDraft(message)
            model.send()
        }
        val bubble = compose.onNode(hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        val id = (model.timeline.value as RecordsState.Loaded).records.single().id
        val confirmation = compose.onNodeWithText("删除这条记录？")
        fun delete() {
            bubble.performTouchInput { longClick() }
            compose.mainClock.advanceTimeBy(650)
            compose.onNodeWithContentDescription("删除记录", useUnmergedTree = true).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(400)
            confirmation.assertIsDisplayed()
            compose.onNodeWithText("删除").performTouchInput { click() }
            compose.waitUntil(5_000) {
                !confirmation.isDisplayed() && (model.timeline.value as? RecordsState.Loaded)?.records?.isEmpty() == true
            }
            compose.mainClock.advanceTimeBy(600)
        }
        delete()
        val undo = compose.onNodeWithText("撤销")
        compose.waitUntil(5_000) { undo.isDisplayed() }
        check(undo.fetchSemanticsNode().boundsInRoot.bottom < compose.onRoot().fetchSemanticsNode().boundsInRoot.height / 2)
        undo.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        check((model.timeline.value as RecordsState.Loaded).records.single().id == id)

        delete()
        compose.mainClock.advanceTimeBy(4_500)
        undo.assertDoesNotExist()
        openStorageSettings()
        compose.onNode(pageList)
            .performScrollToNode(hasText("最近删除"))
        compose.onNodeWithText("最近删除").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(800)
        val restore = compose.onNodeWithText("恢复记录")
        compose.waitUntil(5_000) { restore.isDisplayed() }
        compose.onNodeWithText(message).assertIsDisplayed()
        restore.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            compose.onNodeWithText("没有最近删除的记录").isDisplayed() &&
                (model.timeline.value as? RecordsState.Loaded)?.records?.singleOrNull()?.id == id
        }
        check((model.timeline.value as RecordsState.Loaded).records.single().id == id)
    }

    @Test
    fun attachmentManagerLocatesTheOriginalRecordFromSettings() {
        val source = File(compose.activity.cacheDir, "managed.txt").apply { writeText("附件管理测试") }
        try {
            compose.runOnIdle {
                val authority = "yinu.management.test"
                val provider = PreviewFileSource(source, "text/plain")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
                val uri = Uri.parse("content://$authority/${source.name}")
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(listOf(uri))
            }
            val attachment = compose.onNode(hasText(source.name) and hasClickAction())
            compose.waitUntil(5_000) { attachment.isDisplayed() }
            openStorageSettings()
            compose.onNodeWithText("附件管理").performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(800)
            val locate = compose.onNodeWithText("定位记录")
            compose.waitUntil(5_000) { locate.isDisplayed() }
            compose.onNodeWithText("另存附件").assertIsEnabled()
            locate.performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(800)
            compose.waitUntil(5_000) { !locate.isDisplayed() && attachment.isDisplayed() }
        } finally {
            source.delete()
        }
    }

    private fun usesDarkSystemBarIcons(): Boolean = compose.runOnIdle {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars && controller.isAppearanceLightNavigationBars
    }

    @Test
    fun addButtonExpandsAndBackClosesAttachmentMenu() {
        compose.onNodeWithContentDescription("添加附件").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("选择图片").assertIsDisplayed()
        compose.onNodeWithText("选择文件").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(750)
        compose.onNodeWithText("选择图片").assertDoesNotExist()
    }

    @Test
    fun voiceModeUsesTheComposerAndKeepsTheTextDraft() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
            model.updateDraft("切换语音后保留的草稿")
        }
        compose.onNodeWithContentDescription("切换语音输入").performTouchInput { click() }
        compose.onNodeWithContentDescription("按住说话").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换文字输入").performTouchInput { click() }
        compose.onNodeWithContentDescription("按住说话").assertDoesNotExist()
        compose.onNodeWithText("切换语音后保留的草稿").assertIsDisplayed()
    }

    @Test
    fun messageMenuOpensExtractedInformation() {
        val message = "地址：上海市徐汇区漕溪北路88号 电话：13800138000"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val bubble = compose.onNode(hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and !hasSetTextAction())
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("提取信息", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onNodeWithText("13800138000").isDisplayed() }
        compose.onNodeWithText("上海市徐汇区漕溪北路88号").assertIsDisplayed()
        compose.onNodeWithText("拨号").assertIsDisplayed()
        compose.onNodeWithText("地图").assertIsDisplayed()
    }

    @Test
    fun longPressOpensMessageActionsAndCanFavorite() {
        val message = "浮层回归测试"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val bubble = hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and !hasSetTextAction()
        compose.waitUntil(5_000) { compose.onNode(bubble).isDisplayed() }
        compose.onNode(bubble).performTouchInput { click() }
        compose.onNodeWithContentDescription("收藏", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(bubble).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("收藏", useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已收藏").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aiOrganizeIsDiscoverableFromOneRecordBeforeSetup() {
        val message = "整理入口测试"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val bubble = compose.onNode(hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("AI 整理", useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("请到「设置 → AI 服务」填写 API Key，再打开开关。").assertIsDisplayed()
        compose.onNodeWithText("发送并整理").assertIsNotEnabled()
    }

    @Test
    @Config(shadows = [SelectionMagnifierShadow::class])
    fun longPressCanCopyOnlyTheSelectedRange() {
        val message = "before middle after"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val bubble = compose.onNode(hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("选择文字", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)

        val copy = compose.onNodeWithText("复制选中").assertIsNotEnabled()
        val body = compose.onNodeWithContentDescription("消息正文")
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val middle = layouts.single().getBoundingBox(message.indexOf("middle") + 2).center
        body.performTouchInput { longClick(middle) }
        copy.assertIsEnabled()
        // Narrow the initial word selection to an exact range, as the handles do.
        val start = message.indexOf("middle")
        body.performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
            check(setSelection(start + 1, start + 4, false))
        }
        copy.assertIsEnabled().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        copy.assertDoesNotExist()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            val copied = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            check(copied == "idd") { "Unexpected clipboard text: $copied" }
        }
        bubble.assertIsDisplayed()
    }

    @Test
    fun sendCapturesTheInputPositionAndDoesNotReplayAfterRecreation() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        val text = "从输入框衔接\n第二行内容"
        val input = compose.onNode(hasSetTextAction() and !hasContentDescription("搜索正文、链接或文件名"))
        input.performTouchInput { click() }
        input.performTextInput(text)
        val field = input.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val origin = checkNotNull(model.composerOrigin)
            check(origin.x >= field.left && origin.x < field.right)
            check(origin.y >= field.top && origin.y < field.bottom)
        }
        compose.onNodeWithContentDescription("发送").performTouchInput { click() }
        val bubble = compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        compose.mainClock.advanceTimeBy(800)
        val id = (model.timeline.value as RecordsState.Loaded).records.single().id
        check(!model.hasPendingBubbleAnimation(id))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        check(!ViewModelProvider(compose.activity)[RecordsViewModel::class.java].hasPendingBubbleAnimation(id))
    }

    @Test
    fun multiSelectionLinksTwoOldRecordsToTheNextMessage() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        for (text in listOf("关联材料甲", "关联材料乙")) {
            compose.runOnIdle { model.updateDraft(text); model.send() }
            compose.waitUntil(5_000) { compose.onNodeWithText(text).isDisplayed() }
        }
        compose.onNodeWithText("关联材料甲").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("多选", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("关联材料乙").performTouchInput { click() }
        compose.onNodeWithText("关联").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { model.links.value.size == 2 }
        compose.onNodeWithText("关联 2 条记录").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and !hasContentDescription("搜索正文、链接或文件名"))
            .performTextInput("合并整理的记录")
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performTouchInput { click() }
        val bubble = compose.onNode(hasText("合并整理的记录") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        compose.runOnIdle {
            check((model.timeline.value as RecordsState.Loaded).records.last().links.size == 2)
            check(model.links.value.isEmpty())
        }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("关联记录", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithText("关联到 · 2").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed().performTouchInput { click() }
    }

    @Test
    fun quickImageActionIsConsumedOnceAndCancelKeepsDraft() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle {
            model.updateDraft("保留的草稿")
            model.prepareQuickAction("image")
        }
        compose.waitUntil(5_000) { model.quickAction.value == null }
        val activity = shadowOf(compose.activity)
        val launched = checkNotNull(activity.nextStartedActivityForResult)
        check(launched.intent.action == Intent.ACTION_OPEN_DOCUMENT)
        compose.runOnIdle { model.prepareQuickAction("image") }
        compose.waitUntil(5_000) { model.quickAction.value == null }
        check(activity.nextStartedActivityForResult == null)
        compose.runOnIdle { activity.receiveResult(launched.intent, android.app.Activity.RESULT_CANCELED, null) }
        check(model.draft.value == "保留的草稿")
        check((model.timeline.value as RecordsState.Loaded).records.isEmpty())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onNode(hasSetTextAction() and hasText("保留的草稿")).isDisplayed() }
        check(shadowOf(compose.activity).nextStartedActivityForResult == null)
    }

    @Test
    fun reminderOpensTheSharedWheelDialogAndCancelDoesNotCreateOne() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle { model.updateDraft("提醒弹窗的记录"); model.send() }
        val bubble = compose.onNodeWithText("提醒弹窗的记录")
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("设置提醒", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("小时，", substring = true).assertExists()
        compose.onNodeWithContentDescription("分钟，", substring = true).assertExists()
        compose.onNodeWithText("取消").assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(650)
        check((model.timeline.value as RecordsState.Loaded).records.single().reminderAt == null)
    }

    @Test
    fun multiSelectionSweepsSelectAndDeselectWithoutRetogglingRows() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        val texts = listOf("滑选第一条", "滑选第二条", "滑选第三条")
        for (text in texts) {
            compose.runOnIdle { model.updateDraft(text); model.send() }
            compose.waitUntil(5_000) { compose.onNodeWithText(text).isDisplayed() }
        }
        compose.onNodeWithText(texts[0]).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("多选", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
            androidx.compose.ui.state.ToggleableState.On)
        compose.onNode(hasText(texts[0]) and selected).assertExists()
        fun stroke() {
            val start = compose.onNodeWithText(texts[2]).fetchSemanticsNode().boundsInRoot.center
            val end = compose.onNodeWithText(texts[1]).fetchSemanticsNode().boundsInRoot.center
            compose.mainClock.autoAdvance = false
            compose.onRoot().performTouchInput {
                down(start)
            }
            compose.mainClock.advanceTimeBy(650)
            compose.onRoot().performTouchInput {
                moveTo(end, delayMillis = 300)
                up()
            }
            compose.mainClock.autoAdvance = true
        }
        stroke()
        compose.onNodeWithText("已选 3 条").assertIsDisplayed()
        stroke()
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
    }

    @Test
    fun multiSelectDeletesOnlyTheConfirmedMessages() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        val kept = "批量操作保留"
        val first = "批量操作第一条"
        val second = "批量操作第二条"
        for (text in listOf(kept, first, second)) {
            compose.runOnIdle {
                model.updateDraft(text)
                model.send()
            }
            val sent = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and !hasSetTextAction()
            compose.waitUntil(5_000) { compose.onNode(sent).isDisplayed() }
        }

        compose.onNodeWithText(first).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("多选", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
        compose.onNodeWithText(second).performTouchInput { click() }
        compose.onNodeWithText("已选 2 条").assertIsDisplayed()

        val delete = compose.onNodeWithContentDescription("删除所选消息")
        delete.performTouchInput { click() }
        compose.onNodeWithText("删除选中的 2 条记录？").assertIsDisplayed()
        compose.onNodeWithText("取消").performTouchInput { click() }
        compose.onNodeWithText("已选 2 条").assertIsDisplayed()
        delete.performTouchInput { click() }
        compose.onNodeWithText("删除").performTouchInput { click() }
        compose.waitUntil(5_000) {
            !delete.isDisplayed() && !compose.onNodeWithText(first).isDisplayed() && !compose.onNodeWithText(second).isDisplayed()
        }
        compose.onNodeWithText(kept).assertIsDisplayed()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
    }

    @Test
    fun pinnedBannerLoadsOlderWindowAndLongPressCanUnpin() {
        val message = "要置顶的记录"
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle {
            model.updateDraft(message)
            model.send()
        }
        val original = compose.onNode(
            hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        )
        compose.waitUntil(5_000) { original.isDisplayed() }
        original.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("置顶", useUnmergedTree = true).performTouchInput { click() }
        val banner = compose.onNodeWithContentDescription("置顶消息")
        compose.waitUntil(5_000) { banner.isDisplayed() }

        val first = (model.timeline.value as RecordsState.Loaded).records.single()
        val seed = LocalStorageModule.database(compose.activity.applicationContext)
        try {
            runBlocking {
                for (index in 1..52) {
                    seed.records().add(
                        MessageEntity("newer-$index", "TEXT", "新的内容 $index", first.createdAt + index),
                        emptyList(),
                    )
                }
            }
        } finally {
            seed.close()
        }
        compose.runOnIdle { model.retry() }
        compose.waitUntil(5_000) { compose.onNodeWithText("新的内容 52").isDisplayed() }
        check((model.timeline.value as RecordsState.Loaded).let { it.hasOlder && it.records.size == 48 })
        original.assertDoesNotExist()
        banner.performTouchInput { click() }
        compose.waitUntil(5_000) { original.isDisplayed() }
        original.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("取消置顶", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(5_000) { !banner.isDisplayed() }
        compose.mainClock.advanceTimeBy(400)
        original.assertIsDisplayed()
        val latest = compose.onNodeWithContentDescription("回到最新记录")
        latest.assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) {
            val visible = compose.onNodeWithText("新的内容 52").isDisplayed()
            val state = model.timeline.value as? RecordsState.Loaded
            visible && state?.records?.size == 48
        }
        compose.onNode(pageList)
            .performScrollToIndex(45)
        compose.waitUntil(5_000) {
            val visible = compose.onNodeWithText("新的内容 7").isDisplayed()
            visible && (model.timeline.value as? RecordsState.Loaded)?.records?.size == 53
        }
    }

    @Test
    fun longPressEditsRecordInPlace() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        val original = "还没修改的记录"
        val revised = "已经修改的记录"
        compose.runOnIdle {
            model.updateDraft(original)
            model.send()
        }
        val bubble = compose.onNode(hasText(original) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { bubble.isDisplayed() }
        val id = (model.timeline.value as RecordsState.Loaded).records.single().id
        bubble.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("编辑记录", useUnmergedTree = true).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        val editor = compose.onNodeWithContentDescription("编辑正文")
        editor.performTextReplacement(revised)
        compose.onNodeWithText("保存修改").performTouchInput { click() }
        val revisedBubble = compose.onNode(hasText(revised) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        compose.waitUntil(5_000) { !editor.isDisplayed() && revisedBubble.isDisplayed() }
        check((model.timeline.value as RecordsState.Loaded).records.single().id == id)
        bubble.assertDoesNotExist()
    }

    @Test
    fun deleteConfirmationUsesBottomDialogAndCancelKeepsRecord() {
        val message = "删除确认测试"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val bubble = hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and !hasSetTextAction()
        compose.waitUntil(5_000) { compose.onNode(bubble).isDisplayed() }
        compose.onNode(bubble).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("删除记录", useUnmergedTree = true)
            .performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("删除这条记录？").assertIsDisplayed()
        val cancel = compose.onNodeWithText("取消")
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        check(cancelBounds.center.y > screen.top + screen.height * 0.75f)
        cancel.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("删除这条记录？").assertDoesNotExist()
        compose.onNode(bubble).assertIsDisplayed()
    }

    @Test
    fun longPressQuoteAppearsInComposerAndIsSavedWithReply() {
        val message = "原消息"
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        compose.runOnIdle {
            model.updateDraft(message)
            model.send()
        }
        val bubble = hasText(message) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and !hasSetTextAction()
        compose.waitUntil(5_000) { compose.onNode(bubble).isDisplayed() }
        compose.onNode(bubble).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithContentDescription("引用", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("引用 · $message").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消引用").assertIsDisplayed()
        compose.runOnIdle {
            model.updateDraft("引用后的记录")
            model.send()
        }
        compose.waitUntil(5_000) { compose.onNodeWithText("引用后的记录").isDisplayed() }
        compose.onNodeWithContentDescription("取消引用").assertDoesNotExist()
        val quote = compose.onNodeWithText("引用 · $message")
        quote.assertIsDisplayed()
        compose.runOnIdle { model.updateQuery("引用后的记录") }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(750)
        compose.waitUntil(5_000) { quote.isDisplayed() }
        compose.onNode(bubble).assertDoesNotExist()
        quote.performTouchInput { longClick() }
        compose.onNodeWithText("查看引用").assertIsDisplayed()
        compose.onNode(bubble).assertDoesNotExist()
        compose.onNodeWithText("查看引用").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onNode(bubble).isDisplayed() }
        compose.onNode(bubble).assertIsDisplayed()
    }

    @Test
    fun downArrowAppearsAwayFromLatestAndReturnsToIt() {
        val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
        val older = (1..35).joinToString("\n") { "旧内容 $it" }
        compose.runOnIdle {
            model.updateDraft(older)
            model.send()
        }
        val olderBubble = compose.onNode(hasText(older) and !hasSetTextAction())
        compose.waitUntil(5_000) { olderBubble.isDisplayed() }
        compose.runOnIdle {
            model.updateDraft("最新的一条")
            model.send()
        }
        val latest = compose.onNodeWithText("最新的一条")
        compose.waitUntil(5_000) { latest.isDisplayed() }
        val down = compose.onNodeWithContentDescription("回到最新记录")
        down.assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.24f, endY = height * 0.78f) }
        compose.mainClock.advanceTimeBy(600)
        down.assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil(5_000) { latest.isDisplayed() }
        compose.mainClock.advanceTimeBy(700)
        down.assertDoesNotExist()
    }

    @Test
    @Config(shadows = [HostFileProvider::class])
    fun imageGallerySwipesAndSharesTheSelectedImage() {
        val names = listOf("gallery-first.png", "gallery-second.png", "gallery-third.png")
        val sources = names.map { File(compose.activity.cacheDir, it).apply {
            writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="))
        } }
        try {
            compose.runOnIdle {
                val uris = sources.mapIndexed { index, file ->
                    val authority = "yinu.gallery.$index"
                    PreviewFileSource(file, "image/png").also { provider ->
                        provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                        ShadowContentResolver.registerProviderInternal(authority, provider)
                    }
                    Uri.parse("content://$authority/${file.name}")
                }
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(uris)
            }
            val first = compose.onNode((hasContentDescription(names[2]) or hasText(names[2])) and hasClickAction())
            compose.waitUntil(5_000) { first.isDisplayed() }
            first.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(650)
            compose.waitUntil(5_000) { compose.onNodeWithText("3 / 3", substring = true).isDisplayed() }
            compose.onNodeWithText("全部图片").assertDoesNotExist()
            compose.onNodeWithText(names[2]).assertIsDisplayed()
            val pager = compose.onNode(hasTestTag("image-gallery"))
            pager.performTouchInput { swipeRight() }
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithText(names[1]).assertIsDisplayed()
            pager.performTouchInput { swipeRight() }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText(names[0]).assertIsDisplayed()
            pager.performTouchInput { swipeLeft() }
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithText(names[1]).assertIsDisplayed()
            compose.onNodeWithContentDescription("分享图片").assertIsDisplayed()
            compose.onNodeWithText(names[1]).assertIsDisplayed()
            compose.onNodeWithContentDescription("分享图片").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle {
                val model = ViewModelProvider(compose.activity)[RecordsViewModel::class.java]
                val current = (model.timeline.value as RecordsState.Loaded).records.single().attachments[1]
                val chooser = checkNotNull(shadowOf(compose.activity).nextStartedActivity)
                val shared = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                check(shared.action == Intent.ACTION_SEND)
                val expected = androidx.core.content.FileProvider.getUriForFile(compose.activity,
                    "${compose.activity.packageName}.attachments", checkNotNull(current.localFile), current.fileName)
                check(shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) == expected)
            }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("分享图片").assertDoesNotExist()
        } finally { sources.forEach { it.delete() } }
    }

    @Test
    fun imageOpensFromTimelineAndSystemBackReturnsToThumbnail() {
        val name = "preview-test.png"
        val source = File(compose.activity.cacheDir, name)
        source.writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=",
        ))
        try {
            compose.runOnIdle {
                val authority = "yinu.preview.test"
                val provider = PreviewFileSource(source, "image/png")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
                val uri = Uri.parse("content://$authority/$name")
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(listOf(uri))
            }
            val imageRow = compose.onNode((hasContentDescription(name) or hasText(name)) and hasClickAction())
            compose.waitUntil(5_000) { imageRow.isDisplayed() }
            imageRow.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithContentDescription("分享图片").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithContentDescription("分享图片").assertDoesNotExist()
            imageRow.assertIsDisplayed()
            imageRow.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithContentDescription(name).performTouchInput { click() }
            compose.mainClock.advanceTimeBy(750)
            compose.onNodeWithContentDescription("分享图片").assertDoesNotExist()
            imageRow.assertIsDisplayed()
        } finally {
            source.delete()
        }
    }

    @Test
    fun markdownOpensInTextPreviewAndBackReturnsToFileCard() {
        val source = File(compose.activity.cacheDir, "preview.md").apply {
            writeText("# 中文文本预览\n这是 Markdown 源文本。", charset("GB18030"))
        }
        try {
            compose.runOnIdle {
                val authority = "yinu.preview.markdown"
                val provider = PreviewFileSource(source, "application/octet-stream")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
                val uri = Uri.parse("content://$authority/${source.name}")
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(listOf(uri))
            }
            val attachment = compose.onNode(hasText(source.name) and hasClickAction())
            compose.waitUntil(5_000) { attachment.isDisplayed() }
            attachment.performTouchInput { click() }
            val body = compose.onNodeWithText("这是 Markdown 源文本。", substring = true)
            compose.waitUntil(5_000) { body.isDisplayed() }
            compose.onNodeWithContentDescription("外部打开").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performTouchInput { click() }
            compose.waitUntil(5_000) { !body.isDisplayed() && attachment.isDisplayed() }
        } finally {
            source.delete()
        }
    }

    @Test
    fun docxAttachmentOpensInYinuAndShowsParagraphsAndTableCells() {
        val name = "preview-word.docx"
        val source = File(compose.activity.cacheDir, name)
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>会议记录</w:t></w:r></w:p>
                    <w:p><w:r><w:t>这是一段 Word 正文。</w:t></w:r></w:p>
                    <w:tbl><w:tr>
                      <w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>已经完成</w:t></w:r></w:p></w:tc>
                    </w:tr></w:tbl>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        try {
            compose.runOnIdle {
                val authority = "yinu.preview.word"
                val provider = PreviewFileSource(source, "application/octet-stream")
                provider.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { this.authority = authority })
                ShadowContentResolver.registerProviderInternal(authority, provider)
                val uri = Uri.parse("content://$authority/$name")
                ViewModelProvider(compose.activity)[RecordsViewModel::class.java].importFiles(listOf(uri))
            }
            val attachment = compose.onNode(hasText(name) and hasClickAction())
            compose.waitUntil(5_000) { attachment.isDisplayed() }
            attachment.performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onNodeWithText("这是一段 Word 正文。").isDisplayed() }
            compose.onNodeWithText("会议记录").assertIsDisplayed()
            compose.onNodeWithText("已经完成").assertIsDisplayed()
            compose.onNodeWithContentDescription("外部打开").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performTouchInput { click() }
            attachment.assertIsDisplayed()
            attachment.performTouchInput { click() }
            val externalOpen = compose.onNodeWithContentDescription("外部打开").assertIsDisplayed()
            val toolbarY = externalOpen.fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(96)
                val movingToolbarY = externalOpen.fetchSemanticsNode().boundsInRoot.top
                check(movingToolbarY > toolbarY) {
                    "The preview toolbar should move toward the attachment card (from $toolbarY to $movingToolbarY)"
                }
                compose.mainClock.advanceTimeBy(400)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            attachment.assertIsDisplayed()
            openSearch()
            compose.onNodeWithText("文档").performTouchInput { click() }
            compose.waitUntil(5_000) { attachment.isDisplayed() }
            attachment.performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onNodeWithText("会议记录").isDisplayed() }
            compose.onNodeWithContentDescription("返回").performTouchInput { click() }
            attachment.assertIsDisplayed()
            compose.onNodeWithText("文档").assertIsDisplayed()
        } finally {
            source.delete()
        }
    }

    @Test
    fun openingSearchFromHomeShowsCategories() {
        openSearch()
        compose.onNodeWithText("按类型浏览").assertIsDisplayed()
        compose.onNodeWithText("文档").assertIsDisplayed()
        compose.onNodeWithText("图片").assertIsDisplayed()
        compose.onNodeWithText("按日期").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("选择日期").assertIsDisplayed()
        fun selectWheel(name: String, value: Float) {
            compose.onNode(hasContentDescription("${name}，", substring = true))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        selectWheel("年份", 2024f)
        selectWheel("月份", 1f)
        selectWheel("日期", 31f)
        selectWheel("月份", 2f)
        compose.onNodeWithText("查看记录").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onNodeWithText("这一天还没有记录").isDisplayed() }
        compose.onNodeWithText("2024/02/29").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        selectWheel("日期", 12f)
        compose.onNodeWithText("取消").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("2024/02/29").assertIsDisplayed()
        compose.onNodeWithText("分类").performTouchInput { click() }
        compose.onNodeWithText("图片").performTouchInput { click() }
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onNodeWithText("这个分类没有匹配的记录").isDisplayed() }
        compose.onNode(hasSetTextAction() and hasContentDescription("搜索正文、链接或文件名")).assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("2024/02/29").assertIsDisplayed()
        compose.onNodeWithText("清除日期").performTouchInput { click() }
        compose.onNodeWithText("按类型浏览").assertIsDisplayed()

        val message = "Nexio search result"
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[RecordsViewModel::class.java].apply {
                updateDraft(message)
                send()
            }
        }
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("搜索正文、链接或文件名"))
        field.performTouchInput { click() }
        field.performTextInput("Nexio")
        compose.waitUntil(5_000) { compose.onNodeWithText(message).isDisplayed() }
        compose.onNodeWithContentDescription("取消搜索").performTouchInput { click() }
        compose.onNodeWithText("按类型浏览").assertIsDisplayed()
        val activity = compose.activity
        compose.runOnIdle {
            activity.onBackPressedDispatcher.onBackPressed()
            check(activity.isFinishing || shadowOf(activity).isTaskMovedToBack) {
                "Back from the search root should leave the app"
            }
        }
    }

    private fun openSearch() {
        // The glass slider records a second copy of the labels for refraction.
        val searchTab = compose.onAllNodesWithText("搜索", useUnmergedTree = true)[0]
        if (searchTab.isDisplayed()) {
            searchTab.performTouchInput { click() }
        } else {
            // Robolectric may give the composer initial keyboard focus, hiding the dock.
            compose.onRoot().performTouchInput { swipeLeft() }
        }
        compose.mainClock.advanceTimeBy(800)
    }
}

/** Legacy host graphics cannot create the system magnifier's Surface. */
@Implements(Magnifier::class)
class SelectionMagnifierShadow {
    @Implementation
    protected fun show(sourceX: Float, sourceY: Float) = Unit

    @Implementation
    protected fun show(sourceX: Float, sourceY: Float, magnifierX: Float, magnifierY: Float) = Unit

    @Implementation
    protected fun dismiss() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class IncomingShareTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun coldTextShareAndWarmFileSharesAreSavedWithoutReplayingOnRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val link = "https://example.com/saved-link"
        val incoming = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        fun contentUri(name: String, authority: String, body: String): Uri {
            val file = File(context.cacheDir, name).apply { writeText(body) }
            val provider = PreviewFileSource(file, "text/plain")
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            return Uri.parse("content://$authority/shared")
        }

        val first = contentUri("分享甲.txt", "yinu.share.first", "first bytes")
        val second = contentUri("分享乙.txt", "yinu.share.second", "second bytes")
        val viewed = contentUri("外部打开.txt", "yinu.share.view", "view bytes")
        val publicIntents = listOf(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"),
            Intent(Intent.ACTION_VIEW).setDataAndType(viewed, "text/plain"),
        )
        for (intent in publicIntents) {
            val candidates = context.packageManager.queryIntentActivities(
                intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY,
            )
            check(candidates.any { it.activityInfo.name == MainActivity::class.java.name })
        }
        ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
            compose.waitUntil(5_000) { compose.onNodeWithText("直接保存").isDisplayed() }
            compose.onNodeWithText("直接保存").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onNodeWithText(link).isDisplayed() }
            scenario.recreate()
            fun deliver(intent: Intent) = scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, intent)
            }
            deliver(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
                putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>("一并分享的说明"))
                clipData = ClipData.newRawUri("first", first)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            compose.waitUntil(5_000) { compose.onNodeWithText("保存分享").isDisplayed() }
            compose.onNodeWithContentDescription("补一句说明（可选）").performTextInput("留下的原因")
            compose.onNodeWithText("保存").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onNodeWithText("留下的原因\n\n一并分享的说明").isDisplayed() }
            deliver(Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://yinu.share.unavailable/missing"), "text/plain",
            ))
            deliver(Intent(Intent.ACTION_VIEW).setDataAndType(viewed, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            compose.waitUntil(5_000) { compose.onNodeWithText("外部打开.txt").isDisplayed() }
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[RecordsViewModel::class.java].timeline.value as RecordsState.Loaded
                check(state.records.size == 3)
                check(state.records.count { it.content == link } == 1)
                val bundle = state.records.single { it.content == "留下的原因\n\n一并分享的说明" }
                check(bundle.attachments.map { it.fileName } == listOf("分享甲.txt", "分享乙.txt"))
                check(bundle.attachments.map { it.localFile?.readText() } == listOf("first bytes", "second bytes"))
                check(state.records.last().attachments.single().localFile?.readText() == "view bytes")
            }
        }
    }

    @Test
    fun viewCannotImportRawFilesOrOurOwnProvider() {
        for (uri in listOf("file:///data/local/example", "content://com.yinu.app.attachments/local/file")) {
            check(IncomingShare.from(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), "com.yinu.app.attachments") == null)
        }
    }
}

private class PreviewFileSource(private val file: File, private val mimeType: String) : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = mimeType
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(file.name)) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}

// Android FileProvider assumes '/' path separators; the Windows JVM fixture uses '\\'.
// Stub only URI construction so the test can assert the actual chosen file and outgoing intent.
@Implements(androidx.core.content.FileProvider::class)
class HostFileProvider {
    companion object {
        @JvmStatic
        @Implementation
        fun getUriForFile(context: android.content.Context, authority: String, file: File, displayName: String?): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(file.absolutePath)
                .appendQueryParameter("displayName", displayName).build()
    }
}
